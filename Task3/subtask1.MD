## Subtask 1. Minio S3-хранилище

### Что в docker-compose

```yaml
minio:
  image: minio/minio:latest
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: minio_user
    MINIO_ROOT_PASSWORD: minio_password
  ports:
    - "9090:9000"   # S3 API
    - "9091:9001"   # Console

minio-init:
  image: minio/mc:latest
  depends_on: [minio]
  entrypoint: >
    /bin/sh -c "
    until /usr/bin/mc alias set local http://minio:9000 minio_user minio_password >/dev/null 2>&1; do sleep 1; done;
    /usr/bin/mc mb -p local/reports-bucket || true;
    /usr/bin/mc anonymous set download local/reports-bucket;
    "
```

`minio-init` — одноразовый контейнер на образе `minio/mc`. Ждёт пока
поднимется Minio, создаёт bucket `reports-bucket`, ставит ему политику
`download` (anonymous read).

### Почему public download

CDN-Nginx ходит в Minio без AWS-подписи. Если bucket требует auth,
пришлось бы:
- либо хранить ключи Minio в конфиге Nginx и подписывать запросы (сложно),
- либо использовать presigned URLs из reports-api (теряем простой кеш по URL).

Public read на bucket с непредсказуемыми именами объектов
(`<username>/<watermark>.json`) — приемлемый компромисс для учебного
проекта. В продакшене — presigned URL с TTL.

### Проверка

```bash
# Web Console
open http://localhost:9091  # minio_user / minio_password

# Что лежит в bucket
docker compose exec -T minio sh -c \
  "mc alias set l http://localhost:9000 minio_user minio_password >/dev/null && \
   mc ls --recursive l/reports-bucket/"
```
