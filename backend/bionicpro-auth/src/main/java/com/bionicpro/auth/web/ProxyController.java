package com.bionicpro.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Прокси к reports-api.
 *
 * WebClient автоматически подкладывает Authorization: Bearer access_token
 * через ServletOAuth2AuthorizedClientExchangeFilterFunction (см. WebClientConfig).
 * При истечении access_token Spring сам сходит за новым через refresh_token.
 */
@RestController
@RequestMapping("/api")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final WebClient reportsApiWebClient;

    public ProxyController(WebClient reportsApiWebClient) {
        this.reportsApiWebClient = reportsApiWebClient;
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> proxyGet(HttpServletRequest request) {
        String path = request.getRequestURI().substring("/api".length());
        String query = request.getQueryString();
        String upstreamUri = query != null ? path + "?" + query : path;

        log.debug("Proxying GET {} to reports-api", upstreamUri);

        try {
            return reportsApiWebClient.get()
                    .uri(upstreamUri)
                    .retrieve()
                    .toEntity(byte[].class)
                    .block();
        } catch (WebClientResponseException e) {
            // Пробрасываем HTTP-статус и тело upstream-а как есть
            return ResponseEntity
                    .status(HttpStatusCode.valueOf(e.getStatusCode().value()))
                    .body(e.getResponseBodyAsByteArray());
        }
    }
}
