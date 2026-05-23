package com.bionicpro.auth.token;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Реализация OAuth2AuthorizedClientRepository, кладущая access/refresh токены в Redis
 * в зашифрованном виде. Ключ привязан к sessionId — токены недоступны вне сессии.
 *
 * Spring Security сам вызывает этот репозиторий при логине (saveAuthorizedClient),
 * при обращении к API (loadAuthorizedClient) и при логауте (removeAuthorizedClient).
 * Refresh access_token по refresh_token тоже делает Spring — мы только хранилище.
 */
@Component
public class EncryptingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    private static final Logger log = LoggerFactory.getLogger(EncryptingAuthorizedClientRepository.class);
    private static final String KEY_PREFIX = "bionicpro:tokens:";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final TokenEncryptor encryptor;
    private final ObjectMapper objectMapper;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public EncryptingAuthorizedClientRepository(
            StringRedisTemplate redis,
            TokenEncryptor encryptor,
            ObjectMapper objectMapper,
            ClientRegistrationRepository clientRegistrationRepository
    ) {
        this.redis = redis;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        String encrypted = redis.opsForValue().get(key(session.getId(), clientRegistrationId));
        if (encrypted == null) {
            return null;
        }

        try {
            TokenSnapshot snap = objectMapper.readValue(encryptor.decrypt(encrypted), TokenSnapshot.class);
            ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(clientRegistrationId);
            if (registration == null) {
                log.warn("ClientRegistration {} not found, dropping stored tokens", clientRegistrationId);
                return null;
            }

            OAuth2AccessToken accessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    snap.accessTokenValue(),
                    snap.accessIssuedAt(),
                    snap.accessExpiresAt(),
                    snap.scopes()
            );
            OAuth2RefreshToken refreshToken = snap.refreshTokenValue() != null
                    ? new OAuth2RefreshToken(snap.refreshTokenValue(), snap.refreshIssuedAt())
                    : null;

            return (T) new OAuth2AuthorizedClient(registration, principal.getName(), accessToken, refreshToken);
        } catch (Exception e) {
            log.error("Failed to load authorized client for session {}", session.getId(), e);
            return null;
        }
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // getSession() = getSession(true) — создаём сессию, если её ещё нет (после первого callback)
        HttpSession session = request.getSession();

        OAuth2AccessToken at = authorizedClient.getAccessToken();
        OAuth2RefreshToken rt = authorizedClient.getRefreshToken();

        TokenSnapshot snap = new TokenSnapshot(
                at.getTokenValue(),
                at.getTokenType().getValue(),
                at.getIssuedAt(),
                at.getExpiresAt(),
                at.getScopes(),
                rt != null ? rt.getTokenValue() : null,
                rt != null ? rt.getIssuedAt() : null
        );

        try {
            String json = objectMapper.writeValueAsString(snap);
            String encrypted = encryptor.encrypt(json);
            redis.opsForValue().set(
                    key(session.getId(), authorizedClient.getClientRegistration().getRegistrationId()),
                    encrypted,
                    TOKEN_TTL
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save authorized client", e);
        }
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        redis.delete(key(session.getId(), clientRegistrationId));
    }

    /**
     * Переносит зашифрованные токены с одного session_id на другой.
     * Вызывается при логине (Spring Security меняет session_id в конце auth-флоу)
     * и при каждой ротации в SessionRotationFilter.
     */
    public void migrate(String oldSessionId, String newSessionId) {
        if (oldSessionId == null || newSessionId == null || oldSessionId.equals(newSessionId)) {
            return;
        }
        String oldKey = key(oldSessionId, CLIENT_REGISTRATION_ID);
        String newKey = key(newSessionId, CLIENT_REGISTRATION_ID);

        String encrypted = redis.opsForValue().get(oldKey);
        if (encrypted == null) {
            return;
        }
        redis.opsForValue().set(newKey, encrypted, TOKEN_TTL);
        redis.delete(oldKey);
        log.debug("Migrated tokens: {} -> {}", oldKey, newKey);
    }

    private static final String CLIENT_REGISTRATION_ID = "keycloak";

    private String key(String sessionId, String clientRegistrationId) {
        return KEY_PREFIX + sessionId + ":" + clientRegistrationId;
    }
}
