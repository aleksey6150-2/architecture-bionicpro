-- Источники данных для ETL: CRM и Telemetry в одном Postgres (две схемы)
-- В реальности CRM это Битрикс24 на Oracle, а telemetry — отдельная PostgreSQL.
-- Для целей курсового проекта моделируем оба источника в одной БД.

CREATE SCHEMA IF NOT EXISTS crm;
CREATE SCHEMA IF NOT EXISTS telemetry;

-- =====================================
-- CRM: пользователи, протезы, заказы
-- =====================================

CREATE TABLE crm.customers (
    customer_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64) UNIQUE NOT NULL,   -- совпадает с preferred_username из Keycloak JWT
    full_name     VARCHAR(256),
    email         VARCHAR(256),
    country_code  VARCHAR(2) DEFAULT 'RU',
    created_at    TIMESTAMP DEFAULT NOW()
);

CREATE TABLE crm.prostheses (
    prosthesis_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id    UUID REFERENCES crm.customers(customer_id),
    model          VARCHAR(64),
    purchase_date  DATE,
    active         BOOLEAN DEFAULT TRUE
);

-- Сидируем пользователями, которые есть в Keycloak (realm + LDAP).
-- username тут == preferred_username в JWT.
INSERT INTO crm.customers (username, full_name, email, country_code) VALUES
    ('user1',       'User One',         'user1@example.com',       'RU'),
    ('user2',       'User Two',         'user2@example.com',       'RU'),
    ('prothetic1',  'Prothetic One',    'prothetic1@example.com',  'RU'),
    ('prothetic2',  'Prothetic Two',    'prothetic2@example.com',  'RU'),
    ('prothetic3',  'Prothetic Three',  'prothetic3@example.com',  'DE'),
    ('john.doe',    'John Doe',         'john@example.com',        'RU'),
    ('jane.smith',  'Jane Smith',       'jane@example.com',        'RU'),
    ('alex.johnson','Alex Johnson',     'alex@example.com',        'DE');

-- по одному протезу на каждого
INSERT INTO crm.prostheses (customer_id, model, purchase_date)
SELECT
    customer_id,
    (ARRAY['BionicPRO Alpha','BionicPRO Beta','BionicPRO Gamma'])[1 + (random()*2)::int],
    CURRENT_DATE - (30 + (random()*180)::int)
FROM crm.customers;

-- =====================================
-- Telemetry: события от протезов
-- =====================================

CREATE TABLE telemetry.events (
    event_id          BIGSERIAL PRIMARY KEY,
    prosthesis_id     UUID NOT NULL,
    event_time        TIMESTAMP NOT NULL,
    event_type        VARCHAR(32) NOT NULL,   -- movement | response | battery_cycle | error
    response_time_ms  INTEGER,
    payload           JSONB
);

CREATE INDEX idx_telemetry_pros_time ON telemetry.events (prosthesis_id, event_time);
CREATE INDEX idx_telemetry_time      ON telemetry.events (event_time);

-- Сидим телеметрию: для каждого протеза 30 дней × ~50 событий/день
INSERT INTO telemetry.events (prosthesis_id, event_time, event_type, response_time_ms)
SELECT
    p.prosthesis_id,
    -- случайный момент в нужном дне
    (CURRENT_DATE - (d.day_offset || ' days')::INTERVAL)::TIMESTAMP
        + (random() * 86400 || ' seconds')::INTERVAL,
    CASE (random() * 10)::INT
        WHEN 0 THEN 'error'
        WHEN 1 THEN 'battery_cycle'
        WHEN 2 THEN 'response'
        WHEN 3 THEN 'response'
        ELSE 'movement'
    END,
    (40 + random() * 120)::INT
FROM crm.prostheses p
CROSS JOIN generate_series(1, 30) d(day_offset)
CROSS JOIN generate_series(1, 50) e(event_idx);

-- =====================================
-- CDC: Debezium через pgoutput
-- =====================================
-- REPLICA IDENTITY FULL — чтобы в WAL писались все колонки старой строки,
-- а не только PK. Это нужно Debezium'у для UPDATE/DELETE-событий,
-- иначе он не сможет восстановить before-state.
ALTER TABLE crm.customers  REPLICA IDENTITY FULL;
ALTER TABLE crm.prostheses REPLICA IDENTITY FULL;

-- Статистика после загрузки
DO $$
DECLARE
    customers_count INT;
    prostheses_count INT;
    events_count INT;
BEGIN
    SELECT COUNT(*) INTO customers_count FROM crm.customers;
    SELECT COUNT(*) INTO prostheses_count FROM crm.prostheses;
    SELECT COUNT(*) INTO events_count FROM telemetry.events;
    RAISE NOTICE 'Seeded: customers=%, prostheses=%, telemetry events=%',
        customers_count, prostheses_count, events_count;
END $$;
