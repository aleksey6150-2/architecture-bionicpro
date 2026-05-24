## Subtask 5. Airflow без CRM, reports-api на mart_v2

### Airflow DAG

[`airflow/dags/user_report_etl.py`](../airflow/dags/user_report_etl.py)
переписан: теперь DAG **не** ходит в CRM, читает только `telemetry.events`
и инсёртит агрегаты в `telemetry_daily_agg`. ClickHouse-MV сам сджоинит
с CDC-CRM-таблицами и наполнит финальную витрину.

```python
SELECT
    e.prosthesis_id,
    COUNT(*) FILTER (WHERE e.event_type = 'movement')      AS total_movements,
    COUNT(e.event_id)                                      AS total_events,
    AVG(e.response_time_ms)::float                         AS avg_response_time_ms,
    MAX(e.response_time_ms)::float                         AS max_response_time_ms,
    COUNT(*) FILTER (WHERE e.event_type = 'battery_cycle') AS battery_cycles,
    COUNT(*) FILTER (WHERE e.event_type = 'error')         AS error_count
FROM telemetry.events e
WHERE e.event_time >= ...
GROUP BY e.prosthesis_id
```

Запрос лёгкий, идёт только в `telemetry.events`. CRM-таблицы
(`crm.customers`, `crm.prostheses`) не упоминаются — никакого
**SELECT'а на CRM** в момент ETL **нет**.

Для идемпотентности перед INSERT'ом чистим `telemetry_daily_agg` и
`user_report_mart_v2` за тот же день. Watermark обновляется как раньше.

### reports-api

[`ReportRepository.java`](../backend/reports-api/src/main/java/com/bionicpro/reports/db/ReportRepository.java)
поменялась одна строка SQL:

```diff
-                    FROM user_report_mart
+                    FROM user_report_mart_v2
```

Структура колонок та же — клиент ничего не заметил, фронту менять
ничего не пришлось. Кеш в S3 продолжает работать ровно так же
(Task 3 поверх Task 4 не сломан).

### Что получили в итоге

- **OLTP CRM (Postgres)** — не дёргается аналитикой. Только writes от
  CRM-приложения + WAL-чтение Debezium'ом (write-only с точки зрения CRM,
  это самое лёгкое возможное чтение).
- **Любая правка в CRM** доходит до витрины асинхронно через Kafka,
  без cron'а Airflow.
- **Airflow** делает только telemetry-агрегацию — стало быстрее
  (не нужно агрегировать миллион ивентов с JOIN'ом на customers/prostheses).
- **reports-api** работает поверх `user_report_mart_v2`, в которой JOIN
  уже выполнен MV-ом.

### Проверка end-to-end

1. http://localhost:9000/oauth2/authorization/keycloak → логин `user1`
2. Жмём Download Report
3. Получаем файл `report-2026-05-23.json`
4. Содержимое — данные из `user_report_mart_v2`, поля заполнены и
   из CRM (full_name, email, prosthesis_model) и из telemetry
   (total_movements, avg_response_time_ms, ...).
