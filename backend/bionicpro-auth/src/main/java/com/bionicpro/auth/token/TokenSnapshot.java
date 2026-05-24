package com.bionicpro.auth.token;

import java.time.Instant;
import java.util.Set;

/**
 * Сериализуемое представление OAuth2AuthorizedClient.
 * Содержит только то, что нужно хранить — без ClientRegistration и Principal,
 * которые восстанавливаются на стороне приложения.
 */
public record TokenSnapshot(
        String accessTokenValue,
        String tokenType,
        Instant accessIssuedAt,
        Instant accessExpiresAt,
        Set<String> scopes,
        String refreshTokenValue,
        Instant refreshIssuedAt
) {
}
