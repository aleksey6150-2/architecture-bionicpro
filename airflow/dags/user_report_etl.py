"""
ETL для витрины отчётов.

После внедрения CDC через Debezium (Task 4) DAG больше **не ходит** в
CRM-таблицы: CRM-сущности приезжают в ClickHouse через Kafka и
поддерживают актуальное состояние в crm_customers_latest / crm_prostheses_latest.

Здесь готовим только агрегаты телеметрии за день и инсёртим в
telemetry_daily_agg. ClickHouse-MV mv_telemetry_to_mart_v2 джоинит наши
данные с CDC-таблицами CRM и пишет финальную витрину user_report_mart_v2.
"""
from datetime import date, datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator


def _run_etl(ds: str, **_):
    """ds — execution date в формате YYYY-MM-DD, прокинут Airflow."""
    import psycopg2
    from clickhouse_driver import Client

    target_date_str = ds
    target_date = date.fromisoformat(ds)
    print(f"ETL for date {target_date}")

    # Extract: только агрегаты телеметрии по протезам за день.
    # CRM-таблицы НЕ читаются — нагрузка на CRM-OLTP исключена.
    pg = psycopg2.connect(
        host="source-db", port=5432, dbname="source",
        user="source", password="source",
    )
    try:
        with pg.cursor() as cur:
            cur.execute(
                """
                SELECT
                    e.prosthesis_id,
                    COUNT(*) FILTER (WHERE e.event_type = 'movement')      AS total_movements,
                    COUNT(e.event_id)                                       AS total_events,
                    COALESCE(AVG(e.response_time_ms), 0)::float             AS avg_response_time_ms,
                    COALESCE(MAX(e.response_time_ms), 0)::float             AS max_response_time_ms,
                    COUNT(*) FILTER (WHERE e.event_type = 'battery_cycle') AS battery_cycles,
                    COUNT(*) FILTER (WHERE e.event_type = 'error')         AS error_count
                FROM telemetry.events e
                WHERE e.event_time >= %s::DATE
                  AND e.event_time <  %s::DATE + INTERVAL '1 day'
                GROUP BY e.prosthesis_id
                """,
                (target_date_str, target_date_str),
            )
            rows = cur.fetchall()
    finally:
        pg.close()

    print(f"Extracted {len(rows)} prosthesis-aggregates from telemetry")

    if not rows:
        print("No telemetry for date, skipping load")
        return

    mart_rows = [
        {
            "prosthesis_id": r[0],
            "report_date": target_date,
            "total_movements": int(r[1] or 0),
            "total_events": int(r[2] or 0),
            "avg_response_time_ms": float(r[3] or 0),
            "max_response_time_ms": float(r[4] or 0),
            "battery_cycles": int(r[5] or 0),
            "error_count": int(r[6] or 0),
        }
        for r in rows
    ]

    ch = Client(host="clickhouse", port=9000, database="bionicpro_olap",
                user="ch", password="ch")

    # Идемпотентность: дубли за день схлопнет ReplacingMergeTree по inserted_at.
    # Но MV всё равно отработает на каждый инсёрт. Чтобы не плодить дубли в
    # user_report_mart_v2, чистим mart за этот день перед инсёртом в источник MV.
    ch.execute(
        "ALTER TABLE telemetry_daily_agg DELETE WHERE report_date = %(d)s",
        {"d": target_date_str},
    )
    ch.execute(
        "ALTER TABLE user_report_mart_v2 DELETE WHERE report_date = %(d)s",
        {"d": target_date_str},
    )

    ch.execute(
        """
        INSERT INTO telemetry_daily_agg (
            prosthesis_id, report_date, total_movements, total_events,
            avg_response_time_ms, max_response_time_ms,
            battery_cycles, error_count
        ) VALUES
        """,
        mart_rows,
    )
    print(f"Inserted {len(mart_rows)} rows into telemetry_daily_agg "
          f"(MV распределит в user_report_mart_v2 через JOIN с CDC-CRM)")

    # Watermark (legacy таблица — пусть продолжает быть истиной для reports-api)
    ch.execute(
        "INSERT INTO etl_watermark (pipeline, processed_through) VALUES",
        [{"pipeline": "user_report", "processed_through": target_date}],
    )
    print(f"Watermark updated to {target_date_str}")


with DAG(
    dag_id="user_report_etl",
    description="ETL: telemetry aggregate → telemetry_daily_agg "
                "(CRM приезжает через CDC, не дёргается отсюда)",
    start_date=datetime(2026, 4, 1),
    schedule="@daily",
    catchup=True,
    max_active_runs=1,
    default_args={
        "owner": "bionicpro",
        "retries": 1,
        "retry_delay": timedelta(minutes=2),
    },
    tags=["bionicpro", "etl", "clickhouse", "cdc"],
) as dag:
    etl = PythonOperator(
        task_id="extract_transform_load",
        python_callable=_run_etl,
        op_kwargs={"ds": "{{ ds }}"},
    )
