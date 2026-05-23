Конфиг IdP
[realm-export.json](../keycloak/realm-export.json)

Скриншот вкладки Identity Providers
![identity-providers.png](img/identity-providers.png)

Скриншот вкладки Mappers
![mappers.png](img/mappers.png)

Скриншот страницы логина с кнопкой "Яндекс ID"
![yapr-login.png](img/yapr-login.png)

Скриншот consent screen
![consest.png](img/consest.png)

SQL-выдача по user_consent
![sql-users.png](img/sql-users.png)


интеграция с Яндексом подтверждена структурно; для полного E2E теста нужна регистрация приложения с redirect_uri = http://localhost:8080/realms/reports-realm/broker/yandex/endpoint


