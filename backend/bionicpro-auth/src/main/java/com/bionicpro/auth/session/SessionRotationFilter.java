package com.bionicpro.auth.session;

import com.bionicpro.auth.token.EncryptingAuthorizedClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Ротирует session_id при каждом запросе к защищённому ресурсу.
 * Защита от session fixation и долгоживущей утечки cookie:
 * каждое использование cookie приводит к её замене на новую.
 *
 * Spring Session под капотом переносит сессию в Redis на новый key и
 * выставляет новый Set-Cookie. Наш TokenStore (отдельный неймспейс) мигрируется
 * через {@link EncryptingAuthorizedClientRepository#migrate}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class SessionRotationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionRotationFilter.class);

    private static final List<String> PROTECTED_PATHS = List.of("/api/**", "/me");

    private final EncryptingAuthorizedClientRepository tokenStore;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public SessionRotationFilter(EncryptingAuthorizedClientRepository tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PROTECTED_PATHS.stream().noneMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);

        if (session != null && authentication != null && authentication.isAuthenticated()) {
            String oldSessionId = session.getId();
            String newSessionId = request.changeSessionId();
            tokenStore.migrate(oldSessionId, newSessionId);
            log.debug("Rotated session {} -> {}", oldSessionId, newSessionId);
        }

        chain.doFilter(request, response);
    }
}
