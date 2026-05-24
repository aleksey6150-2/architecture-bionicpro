## Subtask 1. Postgres logical replication

Чтобы Debezium читал WAL Postgres, нужен `wal_level=logical` и
`REPLICA IDENTITY FULL` на каждой захватываемой таблице.

### docker-compose.yaml

```yaml
source-db:
  image: postgres:16
  command:
    - "postgres"
    - "-c"
    - "wal_level=logical"
    - "-c"
    - "max_wal_senders=10"
    - "-c"
    - "max_replication_slots=10"
```

`max_wal_senders` и `max_replication_slots` нужны под слоты репликации —
Debezium создаёт `debezium_crm_slot`.

### source-db/init.sql

```sql
-- REPLICA IDENTITY FULL — в WAL пишутся все колонки старой строки,
-- а не только PK. Это нужно Debezium для UPDATE/DELETE-событий
-- (иначе before-state не восстановить).
ALTER TABLE crm.customers  REPLICA IDENTITY FULL;
ALTER TABLE crm.prostheses REPLICA IDENTITY FULL;
```

Альтернатива — `REPLICA IDENTITY USING INDEX <pk_index>` (только PK),
но тогда before-state в событиях ограничен PK-полями. FULL — самое
безопасное, в обмен на чуть больший объём WAL.

### Проверка после старта

```bash
docker compose exec -T source-db psql -U source -d source -c \
  "SHOW wal_level; SELECT slot_name, plugin, active FROM pg_replication_slots;"
```

Ожидаем `wal_level = logical` и слот `debezium_crm_slot` с
`plugin=pgoutput`, `active=t` (после регистрации коннектора).
