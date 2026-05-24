package com.bionicpro.reports.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ReportRepository {

    private static final Logger log = LoggerFactory.getLogger(ReportRepository.class);

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate findWatermark(String pipeline) {
        try {
            String sql = "SELECT toString(max(processed_through)) FROM etl_watermark "
                    + "WHERE pipeline = '" + sanitize(pipeline) + "'";
            return jdbc.query(sql, rs -> {
                if (!rs.next()) return null;
                String s = rs.getString(1);
                if (s == null || s.isEmpty() || "1970-01-01".equals(s)) return null;
                return LocalDate.parse(s);
            });
        } catch (Exception e) {
            log.error("findWatermark failed for pipeline={}", pipeline, e);
            return null;
        }
    }

    public List<Map<String, Object>> findUserReport(String username, LocalDate maxDate) {
        try {
            String sql = """
                    SELECT toString(report_date) AS report_date_iso,
                           full_name, email, country_code,
                           prosthesis_model,
                           toString(purchase_date) AS purchase_date_iso,
                           total_movements, total_events,
                           avg_response_time_ms, max_response_time_ms,
                           battery_cycles, error_count
                    FROM user_report_mart
                    WHERE username = '%s'
                      AND report_date <= toDate('%s')
                    ORDER BY report_date DESC
                    LIMIT 30
                    """.formatted(sanitize(username), maxDate.toString());

            return jdbc.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", rs.getString("report_date_iso"));
                row.put("full_name", rs.getString("full_name"));
                row.put("email", rs.getString("email"));
                row.put("country_code", rs.getString("country_code"));
                row.put("prosthesis_model", rs.getString("prosthesis_model"));
                row.put("purchase_date", rs.getString("purchase_date_iso"));
                row.put("total_movements", rs.getLong("total_movements"));
                row.put("total_events", rs.getLong("total_events"));
                row.put("avg_response_time_ms", rs.getDouble("avg_response_time_ms"));
                row.put("max_response_time_ms", rs.getDouble("max_response_time_ms"));
                row.put("battery_cycles", rs.getLong("battery_cycles"));
                row.put("error_count", rs.getLong("error_count"));
                return row;
            });
        } catch (Exception e) {
            log.error("findUserReport failed for username={} maxDate={}", username, maxDate, e);
            return List.of();
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("'", "").replace(";", "").replace("\\", "");
    }
}
