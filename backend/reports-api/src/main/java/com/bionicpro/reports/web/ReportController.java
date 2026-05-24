package com.bionicpro.reports.web;

import com.bionicpro.reports.db.ReportRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /reports — единственный публичный эндпоинт. user_id извлекается из JWT,
 * параметрами не принимается, чтобы юзер не мог запросить чужой отчёт.
 */
@RestController
public class ReportController {

    private final ReportRepository repository;

    public ReportController(ReportRepository repository) {
        this.repository = repository;
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

        // 1. Узнаём, до какой даты пайплайн обработал данные.
        LocalDate watermark = repository.findWatermark("user_report");
        if (watermark == null) {
            response.put("period_to", null);
            response.put("days", List.of());
            response.put("note", "ETL ещё не обработал ни одной даты");
            return ResponseEntity.ok(response);
        }

        // 2. Берём отчёт только за период, который Airflow реально загрузил.
        List<Map<String, Object>> days = repository.findUserReport(username, watermark);
        response.put("period_to", watermark.toString());
        response.put("days", days);
        return ResponseEntity.ok(response);
    }
}
