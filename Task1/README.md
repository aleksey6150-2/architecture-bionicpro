# Задание 1. Повышение безопасности системы

Спринт 9 курса "Архитектор ПО" по кейсу BionicPRO. После утечки ПДн пилотов
нужно усилить SSO: вынести токены за пределы браузера, ввести MFA, развести
учётки по странам представительства и оставить расширяемость под внешние IdP.

## Что сделано

| # | Подзадача                                     | Артефакт                                                           |
|---|-----------------------------------------------|--------------------------------------------------------------------|
| 1 | Архитектурное решение + C4                    | [`subtask1.MD`](subtask1.MD), [`c4/task1.drawio`](c4/task1.drawio) |
| 2 | Замена Code Grant на PKCE                     | [`subtask2-3.MD`](subtask2-3.MD)                                   |
| 3 | BFF `bionicpro-auth` (Java 25, Spring Boot 4) | [`subtask2-3.MD`](subtask2-3.MD)                                   |
| 4 | OpenLDAP + User Federation + маппинг ролей    | [`subtask4.MD`](subtask4.MD)                                       |
| 5 | OTP MFA в Keycloak                            | [`subtask5.MD`](subtask5.MD)                                       |
| 6 | Identity Brokering с Яндекс ID                | [`subtask6.MD`](subtask6.MD)                                       |

## Ключевые решения

- **BFF-паттерн.** Между React-фронтом и Keycloak стоит `bionicpro-auth`.
  Он ведёт OAuth-флоу (PKCE), держит access/refresh в Redis под AES-GCM,
  отдаёт в браузер только session cookie (HttpOnly + Secure).
- **Ротация session_id** на каждом запросе к защищённому ресурсу;
  токены мигрируются на новый session_id в Redis.
- **LDAP federation с `importEnabled=false`** — ПДн пилотов физически
  остаются в LDAP представительства, не копируются в Keycloak.
- **OTP MFA** через `requiredActions` + дефолтный browser-flow Keycloak
  (без модификации auth-flow, чтобы не сломать админку).
- **Identity Brokering** Яндекс ID настроен через OIDC v1.0 с маппингом
  атрибутов и consent screen на клиенте `bionicpro-auth`.

## Где что лежит в репозитории

- [`backend/bionicpro-auth/`](../backend/bionicpro-auth) — код BFF
- [`frontend/`](../frontend) — React, переписан на cookie-схему
- [`keycloak/realm-export.json`](../keycloak/realm-export.json) — клиенты,
  federation, identity providers, OTP-политика
- [`ldap/config.ldif`](../ldap/config.ldif) — bootstrap OpenLDAP
- [`docker-compose.yaml`](../docker-compose.yaml) — keycloak, keycloak_db,
  openldap, redis, frontend
