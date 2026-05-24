## Subtask 5

Кнопка скачивания отчёта во фронте — [`frontend/src/components/ReportPage.tsx`](../frontend/src/components/ReportPage.tsx).

Кнопка `Download Report` была ещё в исходном коде, но дёргала
`http://localhost:8000/reports` напрямую с собственным Bearer-токеном
из `keycloak-js`. После Task 1 переехала на cookie-based flow через BFF.

### Что делает

```tsx
const downloadReport = async () => {
  const response = await fetch(`${BFF_URL}/api/reports`, {
    credentials: 'include',
  });
  if (response.status === 401) { setUser(null); return; }
  if (!response.ok) throw new Error(`Report failed: ${response.status}`);

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'report';
  a.click(); a.remove();
  URL.revokeObjectURL(url);
};
```

### Цепочка вызовов

```
Click Download Report
  → fetch GET http://localhost:9000/api/reports (cookie SESSION=...)
    → BFF: достать access_token из Redis по session_id, расшифровать
    → GET http://reports-api:8000/reports
       Authorization: Bearer <access_token>
       → resource server валидирует JWT (JWKS Keycloak)
       → SELECT ... FROM user_report_mart WHERE username = JWT.preferred_username
    → JSON ответ возвращается клиенту
  → blob сохраняется в файл "report"
```

### Условия

- Без сессии (`/me` → 401) — кнопка не отображается (на странице
  только Login)
- В новой архитектуре фронт **никогда** не видит токен — Bearer
  подкладывает BFF на проксе. Реализация подзадачи закрывает оба
  требования из чеклиста: "только аутентифицированный" + "только свой отчёт"
