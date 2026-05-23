package com.bionicpro.auth.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Возвращает фронту инфу о текущем юзере. Без токенов — только id, имя, роли.
 * Эндпоинт защищён общей политикой SecurityConfig (anyRequest().authenticated()).
 */
@RestController
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal OidcUser user) {
        return Map.of(
                "username", Optional.ofNullable(user.getPreferredUsername()).orElse(user.getSubject()),
                "email", Optional.ofNullable(user.getEmail()).orElse(""),
                "name", Optional.ofNullable(user.getFullName()).orElse(""),
                "roles", extractRealmRoles(user)
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRealmRoles(OidcUser user) {
        // Keycloak кладёт realm-роли в claim "realm_access": { "roles": [...] }
        Map<String, Object> realmAccess = user.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        return roles instanceof List ? (List<String>) roles : List.of();
    }
}
