## Subtask 4. ClickHouse: KafkaEngine + MV → новая витрина

Полная схема — [`clickhouse/02-cdc.sql`](../clickhouse/02-cdc.sql).

### Слой 1. KafkaEngine — сырое чтение из топиков

```sql
CREATE TABLE kafka_crm_customers
(
    customer_id      String,
    username         String,
    full_name        Nullable(String),
    email            Nullable(String),
    country_code     Nullable(String),
    `__deleted`      Nullable(String),
    `__op`           Nullable(String),
    `__source_ts_ms` Nullable(Int64)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list  = 'crm.crm.customers',
    kafka_group_name  = 'clickhouse_crm_customers',
    kafka_format      = 'JSONEachRow',
    kafka_max_block_size = 1024,
    kafka_skip_broken_messages = 100;
```

Это **виртуальная** таблица — ClickHouse работает с ней как с
"чтением из Kafka". Прямой SELECT из неё консьюмит сообщения, поэтому
обычно к ней цепляют только MV-консюмер.

Аналогично — `kafka_crm_prostheses` для второго топика.

### Слой 2. ReplacingMergeTree — актуальный срез CRM

```sql
CREATE TABLE crm_customers_latest
(
    customer_id   String,
    username      String,
    full_name     String,
    email         String,
    country_code  String,
    is_deleted    UInt8,
    version       Int64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY customer_id;
```

`ReplacingMergeTree(version)` — при merge оставляет запись с
**максимальной** `version` для каждого `customer_id`. С `SELECT ... FINAL`
получаем гарантированно-последнюю версию. Версия — это
`__source_ts_ms` из Debezium.

### Слой 3. MV: Kafka → ReplacingMergeTree

```sql
CREATE MATERIALIZED VIEW mv_kafka_to_crm_customers
TO crm_customers_latest AS
SELECT
    customer_id,
    username,
    coalesce(full_name, '')    AS full_name,
    coalesce(email, '')        AS email,
    coalesce(country_code, '') AS country_code,
    if(`__deleted` = 'true', 1, 0) AS is_deleted,
    coalesce(`__source_ts_ms`, toInt64(0)) AS version
FROM kafka_crm_customers;
```

MV срабатывает на каждое сообщение из KafkaEngine-таблицы. Делает
проекцию и пишет в `crm_customers_latest`.

### Слой 4. user_report_mart_v2 + MV-join

Витрина для отчётов — широкая денормализованная таблица той же
структуры, что `user_report_mart` (старая), чтобы reports-api переключился
просто сменой имени:

```sql
CREATE TABLE user_report_mart_v2 (
    username              String,
    report_date           Date,
    full_name             String,
    email                 String,
    country_code          LowCardinality(String),
    prosthesis_model      LowCardinality(String),
    purchase_date         Date,
    total_movements       UInt32,
    total_events          UInt32,
    avg_response_time_ms  Float32,
    max_response_time_ms  Float32,
    battery_cycles        UInt16,
    error_count           UInt16,
    mart_built_at         DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY (username, report_date)
PARTITION BY toYYYYMM(report_date);
```

И MV, который при INSERT в `telemetry_daily_agg` (туда пишет Airflow)
делает JOIN с CDC-таблицами CRM:

```sql
CREATE MATERIALIZED VIEW mv_telemetry_to_mart_v2
TO user_report_mart_v2 AS
SELECT
    c.username,
    t.report_date,
    c.full_name,
    c.email,
    c.country_code,
    p.model AS prosthesis_model,
    p.purchase_date,
    t.total_movements,
    t.total_events,
    t.avg_response_time_ms,
    t.max_response_time_ms,
    t.battery_cycles,
    t.error_count,
    now() AS mart_built_at
FROM telemetry_daily_agg AS t
INNER JOIN (SELECT * FROM crm_prostheses_latest FINAL WHERE is_deleted = 0) AS p
    ON p.prosthesis_id = toString(t.prosthesis_id)
INNER JOIN (SELECT * FROM crm_customers_latest FINAL WHERE is_deleted = 0) AS c
    ON c.customer_id = p.customer_id;
```

`FINAL` в подзапросе гарантирует, что JOIN идёт по последней версии CRM-записи,
а `is_deleted = 0` отсекает мягко удалённых.

### Проверка

```bash
# Сколько CDC-записей долетело
docker compose exec -T clickhouse clickhouse-client -u ch --password ch -q \
  "SELECT count() FROM bionicpro_olap.crm_customers_latest FINAL"

# Что в финальной витрине
docker compose exec -T clickhouse clickhouse-client -u ch --password ch -q \
  "SELECT username, COUNT(*) FROM bionicpro_olap.user_report_mart_v2
   GROUP BY username ORDER BY username"
```
