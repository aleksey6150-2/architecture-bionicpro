## Subtask 4

Начал с 4 подзадания, доработал конфиги, запустил докер:
![st4-docker-run.png](img/st4-docker-run.png)

в User Federation 5 штук Mappers:
![st4-user-federation.png](img/st4-user-federation.png)

Видны и старые юзеры из realm:
![st4-user-real-ldap.png](img/st4-user-real-ldap.png)

Юзеру alex.johnson назначена realm-роль prothetic_user:
![st4-alex-johnson.png](img/st4-alex-johnson.png)

Цепочка работает:

OpenLDAP:
* cn=prothetic_user
* member: alex.johnson
* member: john.doe

Keycloak:
* realm role: prothetic_user
* alex.johnson (federated) 

