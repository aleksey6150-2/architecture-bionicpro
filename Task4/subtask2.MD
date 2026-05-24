## Subtask 2. Kafka + Kafka Connect

### Kafka (KRaft, без Zookeeper)

```yaml
kafka:
  image: apache/kafka:3.8.0
  environment:
    KAFKA_NODE_ID: "1"
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
    KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1"
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1"
  ports:
    - "9092:9092"
```

Single-broker, без репликации (для учебного стенда). KRaft-режим
(broker + controller в одном процессе) убирает необходимость в
Zookeeper.

### Kafka Connect с Debezium

```yaml
kafka-connect:
  # quay.io стабильнее Docker Hub'а, у debezium/connect на Hub'е бывает
  # 'unable to decode token response: unexpected EOF'
  image: quay.io/debezium/connect:2.7.3.Final
  depends_on: [kafka, source-db]
  environment:
    BOOTSTRAP_SERVERS: kafka:9092
    GROUP_ID: bionicpro-connect-cluster
    CONFIG_STORAGE_TOPIC: connect_configs
    OFFSET_STORAGE_TOPIC: connect_offsets
    STATUS_STORAGE_TOPIC: connect_statuses
    CONFIG_STORAGE_REPLICATION_FACTOR: "1"
    OFFSET_STORAGE_REPLICATION_FACTOR: "1"
    STATUS_STORAGE_REPLICATION_FACTOR: "1"
    KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
    CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE: "false"
    CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
  ports:
    - "8083:8083"
```

Образ от Debezium уже содержит коннекторы для Postgres/MySQL/etc. —
ничего вручную ставить не надо. REST API доступен на `:8083`.

### Регистрация коннектора

Init-контейнер на `curlimages/curl` ждёт пока поднимется Kafka Connect,
делает `PUT /connectors/crm-connector/config` с JSON из
`debezium/crm-connector.json`.

Если init-контейнер споткнулся — то же руками:

```bash
curl -sS -X PUT -H "Content-Type: application/json" \
  --data @debezium/crm-connector.json \
  http://localhost:8083/connectors/crm-connector/config
```

### Проверка

```bash
# Список коннекторов
curl -s http://localhost:8083/connectors

# Состояние нашего
curl -s http://localhost:8083/connectors/crm-connector/status | python3 -m json.tool
# state должно быть RUNNING

# Топики в Kafka
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Чтение нескольких сообщений
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic crm.crm.customers --from-beginning --max-messages 3
```
