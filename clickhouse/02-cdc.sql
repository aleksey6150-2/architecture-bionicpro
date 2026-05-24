-- ===========================================================
-- CDC pipeline: Kafka → KafkaEngine → MV → ReplacingMergeTree
-- + новая витрина user_report_mart_v2 через MV-join
-- ===========================================================

USE bionicpro_olap;

-- ---------- 1. Telemetry daily aggregate (target Airflow'а) ----------
-- Airflow теперь пишет сюда только агрегаты телеметрии без CRM-данных.
-- Это исключает SELECT-нагрузку на CRM в момент ETL.
CREATE TABLE IF NOT EXISTS telemetry_daily_agg (
    prosthesis_id          UUID,
    report_date            Date,
    total_movements        UInt32,
    total_events           UInt32,
    avg_response_time_ms   Float32,
    max_response_time_ms   Float32,
    battery_cycles         UInt16,
    error_count            UInt16,
    inserted_at            DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(inserted_at)
ORDER BY (prosthesis_id, report_date)
PARTITION BY toYYYYMM(report_date);

-- ---------- 2. Kafka engine таблицы — сырой read из топиков ----------

CREATE TABLE IF NOT EXISTS kafka_crm_customers
(
    customer_id    String,
    username       String,
    full_name      Nullable(String),
    email          Nullable(String),
    country_code   Nullable(String),
    created_at     Nullable(String),
    `__deleted`    Nullable(String),
    `__op`         Nullable(String),
    `__source_ts_ms` Nullable(Int64)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'crm.crm.customers',
    kafka_group_name = 'clickhouse_crm_customers',
    kafka_format = 'JSONEachRow',
    kafka_max_block_size = 1024,
    kafka_skip_broken_messages = 100;

CREATE TABLE IF NOT EXISTS kafka_crm_prostheses
(
    prosthesis_id  String,
    customer_id    String,
    model          Nullable(String),
    purchase_date  Nullable(String),
    active         Nullable(UInt8),
    `__deleted`    Nullable(String),
    `__op`         Nullable(String),
    `__source_ts_ms` Nullable(Int64)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'crm.crm.prostheses',
    kafka_group_name = 'clickhouse_crm_prostheses',
    kafka_format = 'JSONEachRow',
    kafka_max_block_size = 1024,
    kafka_skip_broken_messages = 100;

-- ---------- 3. ReplacingMergeTree — актуальное состояние CRM ----------
-- Версия = source_ts_ms из Debezium. ReplacingMergeTree оставит запись
-- с максимальной версией для каждого PK. С FINAL получаем актуальный срез.

CREATE TABLE IF NOT EXISTS crm_customers_latest
(
    customer_id    String,
    username       String,
    full_name      String,
    email          String,
    country_code   String,
    is_deleted     UInt8,
    version        Int64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY customer_id;

CREATE TABLE IF NOT EXISTS crm_prostheses_latest
(
    prosthesis_id  String,
    customer_id    String,
    model          String,
    purchase_date  Date,
    active         UInt8,
    is_deleted     UInt8,
    version        Int64
)
ENGINE = ReplacingMergeTree(version)
ORDER BY prosthesis_id;

-- ---------- 4. MV: Kafka → latest-table ----------

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_kafka_to_crm_customers
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

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_kafka_to_crm_prostheses
TO crm_prostheses_latest AS
SELECT
    prosthesis_id,
    customer_id,
    coalesce(model, '') AS model,
    coalesce(toDate(purchase_date), toDate('1970-01-01')) AS purchase_date,
    coalesce(active, toUInt8(0)) AS active,
    if(`__deleted` = 'true', 1, 0) AS is_deleted,
    coalesce(`__source_ts_ms`, toInt64(0)) AS version
FROM kafka_crm_prostheses;

-- ---------- 5. Новая витрина user_report_mart_v2 ----------
-- Денормализованная, по структуре аналогична user_report_mart.
-- Reports-api переключается на неё.

CREATE TABLE IF NOT EXISTS user_report_mart_v2 (
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

-- ---------- 6. MV: telemetry → mart_v2 (с JOIN на CDC-таблицы) ----------
-- Триггерится на INSERT в telemetry_daily_agg. Делает JOIN с актуальным
-- срезом CRM (FINAL гарантирует возврат последней версии для каждого PK).

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_telemetry_to_mart_v2
TO user_report_mart_v2 AS
SELECT
    c.username                             AS username,
    t.report_date                          AS report_date,
    c.full_name                            AS full_name,
    c.email                                AS email,
    c.country_code                         AS country_code,
    p.model                                AS prosthesis_model,
    p.purchase_date                        AS purchase_date,
    t.total_movements                      AS total_movements,
    t.total_events                         AS total_events,
    t.avg_response_time_ms                 AS avg_response_time_ms,
    t.max_response_time_ms                 AS max_response_time_ms,
    t.battery_cycles                       AS battery_cycles,
    t.error_count                          AS error_count,
    now()                                  AS mart_built_at
FROM telemetry_daily_agg AS t
INNER JOIN (SELECT * FROM crm_prostheses_latest FINAL WHERE is_deleted = 0) AS p
    ON p.prosthesis_id = toString(t.prosthesis_id)
INNER JOIN (SELECT * FROM crm_customers_latest FINAL WHERE is_deleted = 0) AS c
    ON c.customer_id = p.customer_id;
