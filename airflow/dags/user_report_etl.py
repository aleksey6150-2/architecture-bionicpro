"""
ETL для витрины user_report_mart.

Каждый день (по расписанию @daily) забирает данные из source-db за прошедший день,
агрегирует телеметрию по протезам, джойнит с CRM и грузит в ClickHouse.
В конце обновляет watermark — до какой даты пайплайн прогнан.
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

    # 1. Extract: CRM + telemetry агрегаты за день
    pg = psycopg2.connect(
        host="source-db", port=5432, dbname="source",
        user="source", password="source",
    )
    try:
        with pg.cursor() as cur:
            cur.execute(
                """
                SELECT
                    c.username,
                    c.full_name,
                    c.email,
                    c.country_code,
                    p.model AS prosthesis_model,
                    p.purchase_date,
                    COUNT(*) FILTER (WHERE e.event_type = 'movement')      AS total_movements,
                    COUNT(e.event_id)                                       AS total_events,
                    COALESCE(AVG(e.response_time_ms), 0)::float             AS avg_response_time_ms,
                    COALESCE(MAX(e.response_time_ms), 0)::float             AS max_response_time_ms,
                    COUNT(*) FILTER (WHERE e.event_type = 'battery_cycle') AS battery_cycles,
                    COUNT(*) FILTER (WHERE e.event_type = 'error')         AS error_count
                FROM crm.customers c
                JOIN crm.prostheses p ON p.customer_id = c.customer_id
                LEFT JOIN telemetry.events e
                    ON e.prosthesis_id = p.prosthesis_id
                   AND e.event_time >= %s::DATE
                   AND e.event_time <  %s::DATE + INTERVAL '1 day'
                GROUP BY c.username, c.full_name, c.email, c.country_code,
                         p.model, p.purchase_date
                """,
                (target_date_str, target_date_str),
            )
            rows = cur.fetchall()
    finally:
        pg.close()

    print(f"Extracted {len(rows)} rows from source-db")

    if not rows:
        print("No data for date, skipping load")
        return

    # 2. Transform: приводим к ожидаемой структуре витрины.
    # ClickHouse Date через нативный протокол требует Python date, не строку.
    mart_rows = [
        {
            "username": r[0],
            "report_date": target_date,
            "full_name": r[1] or "",
            "email": r[2] or "",
            "country_code": r[3] or "",
            "prosthesis_model": r[4] or "",
            "purchase_date": r[5] if isinstance(r[5], date) else date.fromisoformat(str(r[5])),
            "total_movements": int(r[6] or 0),
            "total_events": int(r[7] or 0),
            "avg_response_time_ms": float(r[8] or 0),
            "max_response_time_ms": float(r[9] or 0),
            "battery_cycles": int(r[10] or 0),
            "error_count": int(r[11] or 0),
        }
        for r in rows
    ]

    # 3. Load: идемпотентно — сначала удаляем строки за этот день,
    # потом вставляем заново. ALTER ... DELETE в ClickHouse асинхронный,
    # но для учебной нагрузки этого хватает.
    ch = Client(host="clickhouse", port=9000, database="bionicpro_olap",
                user="ch", password="ch")

    ch.execute(
        "ALTER TABLE user_report_mart DELETE WHERE report_date = %(d)s",
        {"d": target_date_str},
    )
    ch.execute(
        """
        INSERT INTO user_report_mart (
            username, report_date, full_name, email, country_code,
            prosthesis_model, purchase_date,
            total_movements, total_events,
            avg_response_time_ms, max_response_time_ms,
            battery_cycles, error_count
        ) VALUES
        """,
        mart_rows,
    )
    print(f"Inserted {len(mart_rows)} rows into user_report_mart")

    # 4. Watermark
    ch.execute(
        "INSERT INTO etl_watermark (pipeline, processed_through) VALUES",
        [{"pipeline": "user_report", "processed_through": target_date}],
    )
    print(f"Watermark updated to {target_date_str}")


with DAG(
    dag_id="user_report_etl",
    description="ETL: CRM + telemetry → user_report_mart (ClickHouse)",
    start_date=datetime(2026, 4, 1),
    schedule="@daily",
    catchup=True,
    max_active_runs=1,
    default_args={
        "owner": "bionicpro",
        "retries": 1,
        "retry_delay": timedelta(minutes=2),
    },
    tags=["bionicpro", "etl", "clickhouse"],
) as dag:
    etl = PythonOperator(
        task_id="extract_transform_load",
        python_callable=_run_etl,
        op_kwargs={"ds": "{{ ds }}"},
    )
