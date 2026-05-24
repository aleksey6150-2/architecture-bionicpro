# reports-api

Backend для эндпоинта `/reports`. Читает витрину `user_report_mart`
из ClickHouse, отдаёт только данные текущего пользователя.

## Назначение

- Резурс-сервер OAuth 2.0: валидирует JWT, выданный Keycloak (BFF
  ходит сюда с Bearer-токеном)
- Фильтрует выборку из ClickHouse по `username` из claim
  `preferred_username` — пользователь не может посмотреть чужой отчёт
- Не возвращает данные за период, который ещё не обработан Airflow
  (читает таблицу `etl_watermark`)

## Стек

- Java 25, Spring Boot 4
- Spring Security OAuth2 Resource Server
- ClickHouse JDBC + Spring `JdbcTemplate`

## API

```
GET /reports
Authorization: Bearer <JWT>

200 OK
{
  "username": "user1",
  "period_to": "2026-05-22",
  "days": [
    { "date": "2026-05-22", "total_movements": 38, "avg_response_time_ms": 91.2, ... },
    ...
  ]
}
```

## Запуск

```bash
docker compose up -d clickhouse reports-api
```

Только при наличии данных в `user_report_mart` (наполняет Airflow DAG `user_report_etl`).
