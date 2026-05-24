## Subtask 2. reports-api: S3 cache + CDN URL в ответе

### S3ReportService

Класс [`S3ReportService.java`](../backend/reports-api/src/main/java/com/bionicpro/reports/s3/S3ReportService.java)
поверх `io.minio:minio:8.5.17`. Три публичных метода:

```java
boolean exists(String username, LocalDate watermark);
void   store (String username, LocalDate watermark, byte[] json);
String cdnUrl(String username, LocalDate watermark);
```

Ключ объекта — `<username>/<watermark>.json`. CDN URL —
`http://localhost:9080/<key>` (берётся из `cdn.base-url`).

### Обновлённый ReportController

```java
LocalDate watermark = repository.findWatermark("user_report");
boolean fromCache = s3.exists(username, watermark);
if (!fromCache) {
    Map<String, Object> report = buildReport(username, watermark);
    byte[] json = objectMapper.writeValueAsBytes(report);
    s3.store(username, watermark, json);
}
return Map.of(
    "username", username,
    "period_to", watermark.toString(),
    "report_url", s3.cdnUrl(username, watermark),
    "cache", fromCache ? "hit" : "miss"
);
```

ClickHouse в `repository.findUserReport(...)` дёргается **только**
если `!fromCache`. Это и есть снятие нагрузки с OLAP — между обновлениями
watermark данные кешируются в S3.

### Конфиг

```yaml
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9090}
  access-key: ${MINIO_ACCESS_KEY:minio_user}
  secret-key: ${MINIO_SECRET_KEY:minio_password}
  bucket: ${MINIO_BUCKET:reports-bucket}

cdn:
  base-url: ${CDN_BASE_URL:http://localhost:9080}
```

В docker-compose сервис `reports-api` получает endpoint `http://minio:9000`
(внутренний адрес), а в `report_url` отдаётся `http://localhost:9080`
(внешний адрес CDN, доступный из браузера).
