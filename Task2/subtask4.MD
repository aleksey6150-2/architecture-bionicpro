## Subtask 4

Доступ к отчёту — только своему.

### Как реализовано

В контроллере [`ReportController.java`](../backend/reports-api/src/main/java/com/bionicpro/reports/web/ReportController.java)
`username` берётся **только** из JWT:

```java
@GetMapping("/reports")
public ResponseEntity<Map<String, Object>> myReport(@AuthenticationPrincipal Jwt jwt) {
    String username = jwt.getClaimAsString("preferred_username");
    ...
}
```

В сигнатуре эндпоинта нет ни `@RequestParam`, ни `@PathVariable` для
пользователя — параметром передать чужой `user_id` физически невозможно.

Далее запрос в ClickHouse:

```sql
SELECT ... FROM user_report_mart
WHERE username = '<preferred_username из JWT>'
  AND report_date <= toDate('<watermark>')
```

### Что закрывает несколько слоёв защиты

1. **Spring Security** валидирует JWT по JWKS Keycloak. Без валидного
   токена — 401 до контроллера.
2. **JWT неподделываемый** — подписан Keycloak'ом, изменить
   `preferred_username` без приватного ключа нельзя.
3. **API не принимает username параметром** — нет способа запросить
   чужой отчёт через query/body.
4. **Витрина отсортирована по `username`** — PK гарантирует, что
   фильтр применяется индексно и быстро.

### Что НЕ закрыто (вне scope задания)

- Админ-роль с просмотром любого отчёта (нужен отдельный эндпоинт с
  проверкой роли в JWT)
- Доступ к отчётам опекаемого подопечного (нужна модель связи в CRM)
