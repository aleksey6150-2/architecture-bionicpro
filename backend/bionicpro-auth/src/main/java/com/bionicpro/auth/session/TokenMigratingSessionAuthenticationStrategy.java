package com.bionicpro.auth.session;

import com.bionicpro.auth.token.EncryptingAuthorizedClientRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.stereotype.Component;

/**
 * Обёртка над ChangeSessionIdAuthenticationStrategy с миграцией нашего TokenStore.
 *
 * Spring Security при успешной аутентификации сам ротирует session_id (защита от
 * session fixation). Но OAuth2LoginAuthenticationFilter сохраняет токены ДО
 * ротации — под старым session_id. Эта стратегия после делегирования основной
 * ротации переносит токены на новый session_id.
 *
 * Composition вместо наследования: ChangeSessionIdAuthenticationStrategy в
 * Spring Security 7 объявлен final.
 */
@Component
public class TokenMigratingSessionAuthenticationStrategy implements SessionAuthenticationStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenMigratingSessionAuthenticationStrategy.class);

    private final SessionAuthenticationStrategy delegate = new ChangeSessionIdAuthenticationStrategy();
    private final EncryptingAuthorizedClientRepository tokenStore;

    public TokenMigratingSessionAuthenticationStrategy(EncryptingAuthorizedClientRepository tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public void onAuthentication(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws SessionAuthenticationException {
        HttpSession existingSession = request.getSession(false);
        String oldSessionId = existingSession != null ? existingSession.getId() : null;

        delegate.onAuthentication(authentication, request, response);

        HttpSession newSession = request.getSession(false);
        String newSessionId = newSession != null ? newSession.getId() : null;

        if (oldSessionId != null && newSessionId != null && !oldSessionId.equals(newSessionId)) {
            log.debug("Login-time session change: {} -> {}, migrating tokens", oldSessionId, newSessionId);
            tokenStore.migrate(oldSessionId, newSessionId);
        }
    }
}
