# bionicpro-auth

Backend-for-Frontend (BFF) для проекта BionicPRO.

## Назначение

- Принимает запросы от React-фронта, отдаёт ему только сессионную cookie
  (HttpOnly + Secure) — токены IdP не покидают сервер
- Реализует OAuth 2.0 Authorization Code flow с PKCE как confidential client
  к Keycloak
- Хранит access/refresh-токены в Redis в зашифрованном виде, привязанные
  к session_id
- При каждом запросе к защищённому ресурсу ротирует session_id
  (защита от session fixation и от долгоживущих утечек cookie)
- Автоматически обновляет access_token по refresh_token, фронт об этом
  не знает
- Проксирует обращения к `reports-api`, подкладывая в Authorization
  Bearer JWT из своего хранилища

## Стек

- Java 25 LTS, Spring Boot 4.0
- Spring Security + spring-boot-starter-oauth2-client (PKCE)
- Spring Session Data Redis (сессии в Redis)
- WebClient (исходящие HTTP)
- Lombok

## Локальный запуск

Требуется поднятый Keycloak и Redis. Redis ещё не в `docker-compose.yaml`
основного репо — добавим, когда подойдём к фактической реализации.

```bash
cd backend/bionicpro-auth
mvn spring-boot:run
```

По умолчанию слушает порт `9000`.

## Структура

```
src/main/java/com/bionicpro/auth/
  BionicproAuthApplication.java   — точка входа
  config/                          — SecurityConfig, RedisConfig, WebClientConfig
  web/                             — контроллеры: /login, /callback, /me, /api/**
  session/                         — RotatingSessionRepository, фильтр ротации
  token/                           — TokenStore (Redis), TokenEncryptor (AES-GCM)
```

(на данный момент в репозитории только скелет — реализация добавляется по этапам)
