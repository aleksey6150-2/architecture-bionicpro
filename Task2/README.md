# Задание 2. Разработка сервиса отчётов

Спринт 9 курса "Архитектор ПО" по кейсу BionicPRO. Пользователи получают
отчёт о работе своего протеза. Данные собираются из двух источников
(CRM и Telemetry), агрегируются Airflow-пайплайном и кладутся в OLAP-витрину
ClickHouse. Сервис отчётов читает витрину и отдаёт пользователю только
его собственные данные.

## Что сделано

| # | Подзадача                                            | Артефакт                         |
|---|------------------------------------------------------|----------------------------------|
| 1 | Архитектура решения + C4                             | [`subtask1.MD`](subtask1.MD), [`c4/task2.drawio`](c4/task2.drawio) |
| 2 | Airflow DAG со scheduled запуском                    | [`subtask2.MD`](subtask2.MD)     |
| 3 | Бэкенд `reports-api` с эндпоинтом `/reports`         | [`subtask3.MD`](subtask3.MD)     |
| 4 | RBAC — пользователь видит только свой отчёт          | [`subtask4.MD`](subtask4.MD)     |
| 5 | Кнопка скачивания отчёта в UI                        | [`subtask5.MD`](subtask5.MD)     |

## Ключевые решения

- **OLAP в ClickHouse.** Витрина `user_report_mart` — широкая денормализованная
  таблица с `MergeTree` и `ORDER BY (username, report_date)`. Это даёт быстрый
  point-lookup по конкретному пользователю и партиционирование по месяцам.
- **ETL в Airflow.** Один DAG `user_report_etl` со schedule `@daily`,
  `catchup=True`. Идемпотентно — `ALTER ... DELETE WHERE report_date = ?`
  перед инсертом за тот же день.
- **Watermark.** Таблица `etl_watermark` хранит максимальную обработанную
  дату; `reports-api` ограничивает выдачу этим значением, чтобы пользователь
  не видел данных, которых ещё нет.
- **RBAC на уровне SQL.** `username` берётся из JWT-claim `preferred_username`,
  параметром API не принимается. Запрос в ClickHouse идёт с `WHERE username = ?`,
  ключ partition по этой колонке делает фильтр эффективным.
- **Резурс-сервер `reports-api`** валидирует JWT по JWKS Keycloak,
  работает stateless (никаких сессий). BFF проксирует с Bearer-токеном,
  фронт ничего не знает о JWT.

## Где что лежит в репозитории

- [`backend/reports-api/`](../backend/reports-api) — Spring Boot resource server
- [`airflow/dags/user_report_etl.py`](../airflow/dags/user_report_etl.py) — ETL DAG
- [`source-db/init.sql`](../source-db/init.sql) — Postgres-мок CRM + Telemetry
  (2 схемы), сидится тестовыми данными при первом старте
- [`clickhouse/init.sql`](../clickhouse/init.sql) — DDL для `user_report_mart`
  и `etl_watermark`
- [`frontend/src/components/ReportPage.tsx`](../frontend/src/components/ReportPage.tsx)
  — кнопка Download Report, fetch к BFF с `credentials: 'include'`
- [`docker-compose.yaml`](../docker-compose.yaml) — добавлены сервисы
  `source-db`, `clickhouse`, `airflow`, `reports-api`

## Сводный поток данных

```
CRM DB (Oracle)         Telemetry DB (PostgreSQL)
       \                       /
        \                     /
         \    Airflow @daily /
          \   extract + load
           \   /         \  /
            ▼             ▼
       ClickHouse (OLAP)
       ├─ user_report_mart  (MergeTree, ORDER BY username,date)
       └─ etl_watermark     (до какой даты прогнан пайплайн)
            ▲
            │ SELECT WHERE username = JWT.preferred_username
            │       AND report_date <= watermark
            │
        reports-api  ──JWKS──► Keycloak
            ▲
            │ Bearer JWT
            │
       bionicpro-auth (BFF)
            ▲
            │ SESSION cookie (HttpOnly, Secure)
            │
          Browser  (Reports Frontend, React)
            ▲
            │ Download Report
       Пилот протеза
```
