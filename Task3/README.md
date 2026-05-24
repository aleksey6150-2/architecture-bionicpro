# Задание 3. Снижение нагрузки на базу данных

Спринт 9 курса "Архитектор ПО" по кейсу BionicPRO. После запуска отчётов
из Task 2 пользователи стали часто перезапрашивать **один и тот же**
отчёт (между запусками ETL данные не меняются). Каждый клик ходит в OLAP.
Здесь добавляем кеш в S3 + раздачу через CDN, чтобы:
- ClickHouse дёргался только при первой генерации новой версии отчёта
- последующие запросы шли через Nginx-кеш мимо OLAP

## Что сделано

| # | Подзадача                                       | Артефакт                     |
|---|-------------------------------------------------|------------------------------|
| 1 | S3-хранилище (Minio) для отчётов                | [`subtask1.MD`](subtask1.MD) |
| 2 | reports-api пишет/читает S3, возвращает CDN URL | [`subtask2.MD`](subtask2.MD) |
| 3 | CDN на Nginx с proxy_cache                      | [`subtask3.MD`](subtask3.MD) |
| 4 | Кеш-инвалидация + структура хранения            | [`subtask4.MD`](subtask4.MD) |

## Ключевые решения

- **Версионированные ключи в S3.** Ключ объекта =
  `<username>/<watermark>.json`. Когда ETL обрабатывает новый день,
  watermark двигается, отчёт получает **новый ключ** = новый URL =
  новая запись в CDN-кеше. Старый ключ просто перестаёт быть востребованным
  (выселяется по LRU). Никаких active-purge запросов от Airflow в CDN.
- **Read-only public bucket.** Bucket `reports-bucket` открыт на anonymous
  download через `mc anonymous set download`. CDN ходит в Minio без подписи
  запросов, что упрощает Nginx-конфиг.
- **CDN перед S3, не прямо S3.** Браузер ходит в Nginx-кеш, не в Minio.
  Это снижает нагрузку и на Minio, и на сеть, и даёт точку контроля
  (CORS, кеш-политику, метрики).
- **CORS на CDN.** Фронт на `:3000` делает кросс-доменный fetch на CDN
  `:9080`. Nginx прячет CORS-заголовки от Minio (`proxy_hide_header`)
  и отдаёт свои — единственные.
- **Не отдаём данных вне watermark.** `reports-api` сначала читает
  `etl_watermark` из ClickHouse, и ключ S3 строится по этому значению.
  Получить отчёт за период, которого нет в OLAP, физически нельзя.

## Где что лежит в репозитории

- [`cdn/nginx.conf`](../cdn/nginx.conf) — конфиг CDN (Nginx)
- [`backend/reports-api/src/main/java/com/bionicpro/reports/s3/S3ReportService.java`](../backend/reports-api/src/main/java/com/bionicpro/reports/s3/S3ReportService.java)
  — клиент Minio, put/exists/cdn URL
- [`backend/reports-api/src/main/java/com/bionicpro/reports/web/ReportController.java`](../backend/reports-api/src/main/java/com/bionicpro/reports/web/ReportController.java)
  — обновлённый flow: check cache → return CDN URL
- [`frontend/src/components/ReportPage.tsx`](../frontend/src/components/ReportPage.tsx)
  — двухшаговое скачивание (`/api/reports` → CDN URL → fetch)
- [`docker-compose.yaml`](../docker-compose.yaml) — добавлены сервисы
  `minio`, `minio-init`, `cdn`

## Поток с кешем

```
Click Download Report
   │
   ├─► BFF ──► reports-api  GET /reports
   │              │
   │              ├─ SELECT watermark FROM etl_watermark
   │              ├─ S3 statObject reports-bucket/user1/<watermark>.json
   │              │   ├─ HIT  → return CDN URL                      ← OLAP не дёргается
   │              │   └─ MISS → SELECT * FROM user_report_mart
   │              │             put JSON в S3
   │              │             return CDN URL
   │              ▼
   │           JSON { report_url: "http://localhost:9080/user1/<w>.json" }
   │
   └─► fetch CDN URL
          │
          └─► Nginx proxy_cache
               ├─ HIT  → отдаёт из кеша                              ← S3 не дёргается
               └─ MISS → proxy_pass http://minio:9000/reports-bucket/
                        кеширует на 24h, отдаёт ответ
```
