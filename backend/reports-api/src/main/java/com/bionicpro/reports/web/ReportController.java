package com.bionicpro.reports.web;

import com.bionicpro.reports.db.ReportRepository;
import com.bionicpro.reports.s3.S3ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /reports — отдаёт ссылку на CDN, где лежит уже сгенерированный отчёт.
 * Алгоритм:
 *   1. Берём watermark из ClickHouse (до какой даты ETL прошёл)
 *   2. Проверяем S3 — есть ли уже отчёт за этот watermark
 *   3. Если есть — отдаём CDN URL сразу, ClickHouse не дёргаем
 *   4. Если нет — генерим из ClickHouse, кладём в S3, отдаём CDN URL
 *
 * Этим разгружаем OLAP: на одну версию отчёта ClickHouse запрашивается
 * только один раз; все последующие чтения идут через CDN cache.
 */
@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportRepository repository;
    private final S3ReportService s3;
    private final ObjectMapper objectMapper;

    public ReportController(ReportRepository repository,
                            S3ReportService s3,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> myReport(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "preferred_username claim missing in JWT"
            ));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", username);

        LocalDate watermark = repository.findWatermark("user_report");
        if (watermark == null) {
            response.put("period_to", null);
            response.put("report_url", null);
            response.put("note", "ETL ещё не обработал ни одной даты");
            return ResponseEntity.ok(response);
        }

        boolean fromCache = s3.exists(username, watermark);
        if (!fromCache) {
            // Кеш-мисс: дёргаем ClickHouse, генерим JSON, кладём в S3.
            Map<String, Object> report = buildReport(username, watermark);
            byte[] json;
            try {
                json = objectMapper.writeValueAsBytes(report);
            } catch (Exception e) {
                log.error("Failed to serialize report for {}", username, e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "error", "report serialization failed"
                ));
            }
            s3.store(username, watermark, json);
        } else {
            log.debug("Report cache hit for {}/{}", username, watermark);
        }

        response.put("period_to", watermark.toString());
        response.put("report_url", s3.cdnUrl(username, watermark));
        response.put("cache", fromCache ? "hit" : "miss");
        return ResponseEntity.ok(response);
    }

    /**
     * Формирует полный отчёт за период [-∞; watermark]. Этот метод —
     * единственное место, где мы реально дёргаем ClickHouse.
     */
    private Map<String, Object> buildReport(String username, LocalDate watermark) {
        List<Map<String, Object>> days = repository.findUserReport(username, watermark);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("username", username);
        report.put("period_to", watermark.toString());
        report.put("days", days);
        return report;
    }
}
