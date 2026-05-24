## Subtask 2

Airflow DAG для подготовки витрины — [`airflow/dags/user_report_etl.py`](../airflow/dags/user_report_etl.py).

### Структура

- DAG `user_report_etl`, schedule `@daily`, `catchup=True`,
  `start_date=2026-04-01`, `max_active_runs=1`, retries=1
- Один таск `extract_transform_load` (можно разбить на 3, но для
  учебной нагрузки одноблочный понятнее)

### Что делает таск

1. **Extract** — открывает соединение с `source-db`, одним SQL
   достаёт CRM-данные (customers + prostheses) и за-день агрегаты
   `telemetry.events` (`movement` / `response_time_ms` / `battery_cycle` / `error`)
2. **Transform** — приводит результат к структуре витрины: типы (`UInt32` для
   счётчиков, `Float32` для откликов, `Date` для дат), пустые поля → нули
3. **Load** — идемпотентно:
   - `ALTER TABLE user_report_mart DELETE WHERE report_date = ?` (за день)
   - `INSERT INTO user_report_mart ... VALUES` пачкой
   - `INSERT INTO etl_watermark` с новой `processed_through`

### Расписание и бэкфилл

При первом старте Airflow видит `start_date=2026-04-01` и `catchup=True`,
запускает по очереди DAG runs за каждый день от старта до сегодня —
получается около 50 runs, обработанных последовательно (max_active_runs=1).
Дальше — раз в сутки на новую дату.

### Поднимаем Airflow и наблюдаем

```bash
docker compose up -d airflow
docker compose logs airflow 2>&1 | grep -i password  # admin пароль
open http://localhost:8082
```

В UI: DAGs → `user_report_etl` → переключатель ON → grid view покажет
все DAG runs в виде квадратиков (зелёные = успех, красные = fail).

После всех успешных runs:

```bash
docker compose exec clickhouse clickhouse-client -u ch --password ch \
  --database bionicpro_olap -q "
  SELECT username, COUNT(*) days, MIN(report_date), MAX(report_date)
  FROM user_report_mart GROUP BY username ORDER BY username"
```
