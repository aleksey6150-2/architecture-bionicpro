-- OLAP-витрина для отчётов BionicPRO
-- Денормализованная и колоночная: ClickHouse эффективно сжимает по столбцам,
-- ORDER BY (username, report_date) даёт быстрый point-lookup по пользователю.

CREATE DATABASE IF NOT EXISTS bionicpro_olap;

USE bionicpro_olap;

-- ===========================================
-- Витрина: одна строка на (юзер × день)
-- ===========================================

CREATE TABLE IF NOT EXISTS user_report_mart (
    username              String,
    report_date           Date,

    -- Атрибуты из CRM (изменяются медленно, дублирование терпимо)
    full_name             String,
    email                 String,
    country_code          LowCardinality(String),
    prosthesis_model      LowCardinality(String),
    purchase_date         Date,

    -- Метрики из telemetry (агрегаты за день)
    total_movements       UInt32,
    total_events          UInt32,
    avg_response_time_ms  Float32,
    max_response_time_ms  Float32,
    battery_cycles        UInt16,
    error_count           UInt16,

    -- Технические колонки
    etl_inserted_at       DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY (username, report_date)
PARTITION BY toYYYYMM(report_date);

-- ===========================================
-- Watermark: до какой даты пайплайн обработал данные
-- ===========================================

CREATE TABLE IF NOT EXISTS etl_watermark (
    pipeline            String,
    processed_through   Date,
    updated_at          DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY pipeline;
