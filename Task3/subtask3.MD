## Subtask 3. CDN на Nginx

[`cdn/nginx.conf`](../cdn/nginx.conf) — reverse proxy перед Minio с
включённым кешированием статики.

### Ключевые директивы

```nginx
proxy_cache_path /var/cache/nginx
                 levels=1:2
                 keys_zone=reports_cache:10m
                 max_size=512m
                 inactive=24h
                 use_temp_path=off;

location / {
    proxy_cache reports_cache;
    proxy_cache_key "$scheme$request_method$host$request_uri";
    proxy_cache_valid 200 24h;
    proxy_cache_valid 404 1m;
    proxy_cache_use_stale error timeout updating;
    proxy_cache_lock on;
    proxy_cache_revalidate on;

    proxy_pass http://minio:9000/reports-bucket/;
}
```

| Директива                            | Что даёт                                                                       |
|--------------------------------------|--------------------------------------------------------------------------------|
| `proxy_cache_path`                   | Кеш на диске, до 512 МБ, неиспользуемое выселяется через 24ч                   |
| `proxy_cache_valid 200 24h`          | 200-ответы держим в кеше сутки                                                 |
| `proxy_cache_lock on`                | При одновременных кеш-миссах в Minio уходит только один запрос, остальные ждут |
| `proxy_cache_use_stale ... updating` | Пока Nginx обновляет кеш, остальные читают предыдущую версию (нет даунтайма)   |
| `proxy_cache_revalidate on`          | Использует ETag/Last-Modified — экономит трафик на повторных запросах в Minio  |

### CORS

Браузер на `:3000` делает fetch к `:9080` — кросс-домен. Minio сам
выставляет CORS-заголовки, но Nginx тоже добавляет свои. Без зачистки
получались **дубли** ACAO + конфликт с `Allow-Credentials: true`.
Решение:

```nginx
proxy_hide_header Access-Control-Allow-Origin;
proxy_hide_header Access-Control-Allow-Credentials;
proxy_hide_header Access-Control-Allow-Methods;
proxy_hide_header Access-Control-Expose-Headers;
proxy_hide_header Vary;

add_header Access-Control-Allow-Origin "*" always;
add_header Access-Control-Allow-Methods "GET, HEAD, OPTIONS" always;
```

### Проверка работы кеша

```bash
# Первый запрос: MISS, идёт в Minio
curl -i http://localhost:9080/user1/2026-05-23.json 2>&1 | grep -i x-cache

# Второй запрос: HIT, отдан из кеша Nginx
curl -i http://localhost:9080/user1/2026-05-23.json 2>&1 | grep -i x-cache
```

Видим:
```
X-Cache-Status: MISS
X-Cache-Status: HIT
```
