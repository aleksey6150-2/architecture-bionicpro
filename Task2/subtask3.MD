## Subtask 3

Бэкенд-сервис `reports-api` — [`backend/reports-api/`](../backend/reports-api).

### Стек

- Java 25, Spring Boot 4
- Spring Security OAuth2 Resource Server (валидация JWT по JWKS)
- ClickHouse JDBC + Spring `JdbcTemplate`
- Запускается в Docker, порт 8000

### Эндпоинт

```
GET /reports
Authorization: Bearer <JWT>

200 OK
{
  "username": "user1",
  "period_to": "2026-05-23",
  "days": [
    {
      "date": "2026-05-23",
      "full_name": "User One",
      "email": "user1@example.com",
      "country_code": "RU",
      "prosthesis_model": "BionicPRO Beta",
      "purchase_date": "2025-12-01",
      "total_movements": 38,
      "total_events": 50,
      "avg_response_time_ms": 98.7,
      "max_response_time_ms": 159.0,
      "battery_cycles": 5,
      "error_count": 4
    },
    ...
  ]
}
```

Без `Authorization` → 401 (Spring Security сам отбивает на фильтре).

### Куда ходит

1. **Keycloak JWKS** (`http://keycloak:8080/realms/reports-realm/protocol/openid-connect/certs`) —
   валидация подписи JWT. Только jwk-set-uri, без issuer-uri:
   JWT выпущен с `iss=localhost:8080`, а сервис в Docker-сети дотягивается
   до Keycloak по `keycloak:8080` — без issuer-проверки это работает.
2. **ClickHouse** (`jdbc:clickhouse://clickhouse:8123/bionicpro_olap?compress=0`):
   - `SELECT max(processed_through) FROM etl_watermark WHERE pipeline = 'user_report'`
   - `SELECT ... FROM user_report_mart WHERE username = ? AND report_date <= watermark`

### Подводные камни, которые встретились

- **`?compress=0` в URL** — без этого ClickHouse JDBC пытается жать LZ4
  и падает без `lz4-java` в classpath.
- **`<classifier>http</classifier>` ломал Spring Boot fat-jar loader** —
  пришлось вернуться к дефолтному артефакту `clickhouse-jdbc:0.6.5` без
  classifier'а и руками добавить `httpclient5`.
- **`PreparedStatement` падал с `NO_COMMON_TYPE`** при `toDate(?)` —
  использовали inline SQL с экранированием (значения берутся из JWT и
  watermark, не из пользовательского ввода).
- **Алиас `report_date` теням колонку** — `ORDER BY report_date` тогда
  ссылается на `toString()`-результат, и ClickHouse путает типы.
  Переименовали алиасы в `report_date_iso` / `purchase_date_iso`.
