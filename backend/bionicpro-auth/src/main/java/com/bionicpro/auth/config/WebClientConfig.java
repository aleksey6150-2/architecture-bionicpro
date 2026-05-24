package com.bionicpro.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Конфигурация WebClient для исходящих вызовов к reports-api.
 *
 * ServletOAuth2AuthorizedClientExchangeFilterFunction перехватывает каждый запрос,
 * через OAuth2AuthorizedClientManager берёт активный OAuth2AuthorizedClient текущей сессии
 * (это вызовет наш EncryptingAuthorizedClientRepository),
 * сам обновляет access_token по refresh_token при истечении,
 * подкладывает Authorization: Bearer ... в заголовок.
 *
 * Нам не нужно вручную возиться с токенами.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient reportsApiWebClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${bionicpro.auth.upstream.reports-api}") String baseUrl
    ) {
        var filter = new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        filter.setDefaultClientRegistrationId("keycloak");

        return WebClient.builder()
                .baseUrl(baseUrl)
                .apply(filter.oauth2Configuration())
                .build();
    }
}
