## Subtask 3. Debezium PostgreSQL Connector

Конфиг — [`debezium/crm-connector.json`](../debezium/crm-connector.json).

```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "tasks.max": "1",

  "database.hostname": "source-db",
  "database.port": "5432",
  "database.user": "source",
  "database.password": "source",
  "database.dbname": "source",
  "topic.prefix": "crm",

  "schema.include.list": "crm",
  "table.include.list": "crm.customers,crm.prostheses",

  "plugin.name": "pgoutput",
  "publication.autocreate.mode": "filtered",
  "slot.name": "debezium_crm_slot",
  "snapshot.mode": "initial",

  "key.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "key.converter.schemas.enable": "false",
  "value.converter.schemas.enable": "false",

  "transforms": "unwrap",
  "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
  "transforms.unwrap.drop.tombstones": "false",
  "transforms.unwrap.delete.handling.mode": "rewrite",
  "transforms.unwrap.add.fields": "op,source.ts_ms"
}
```

### Разбор полей

| Поле | Что значит |
|---|---|
| `plugin.name: pgoutput` | Встроенный в Postgres плагин логической репликации. Не требует установки чего-либо |
| `publication.autocreate.mode: filtered` | Debezium сам создаст `PUBLICATION` в Postgres только для перечисленных таблиц |
| `slot.name: debezium_crm_slot` | Имя replication-slot. Один слот на один коннектор |
| `snapshot.mode: initial` | При первом запуске сделать full snapshot существующих строк, потом перейти на CDC |
| `topic.prefix: crm` | Префикс имени топика. Полное имя топика: `<prefix>.<schema>.<table>` — `crm.crm.customers` |
| `key.converter`, `value.converter` | JSON, без schema-блока (`schemas.enable: false`) — на выходе чистый JSON |

### SMT `ExtractNewRecordState`

Без SMT каждое событие Debezium имеет такую структуру:

```json
{
  "before": null,
  "after": { "customer_id": "...", "username": "user1", ... },
  "source": {...},
  "op": "c",
  "ts_ms": 1779607565733
}
```

С SMT `unwrap`:

```json
{
  "customer_id": "...",
  "username": "user1",
  ...
  "__deleted": "false",
  "__op": "c",
  "__source_ts_ms": 1779607565733
}
```

Плоский JSON — `ClickHouse Kafka engine` напрямую парсит как `JSONEachRow`,
без вложенных структур. Поля `__op`, `__deleted`, `__source_ts_ms`
добавляются настройкой `add.fields` + `delete.handling.mode: rewrite`.

### `op` codes

- `r` — read (initial snapshot, чтение существующей строки)
- `c` — create (INSERT)
- `u` — update
- `d` — delete (вместе с `__deleted: "true"`)
