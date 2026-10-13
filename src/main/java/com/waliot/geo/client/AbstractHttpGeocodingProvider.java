package com.waliot.geo.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waliot.geo.config.GeocodingProperties;
import com.waliot.geo.exception.GeocodingProviderException;
import com.waliot.geo.exception.GeocodingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Общий HTTP-клиент провайдеров: GET + JSON, ретраи с backoff, учёт Retry-After (429).
 */
public abstract class AbstractHttpGeocodingProvider implements GeocodingProvider {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 500;
    /** Максимальное инлайн-ожидание на 429 в потоке обработки запроса. */
    private static final long MAX_INLINE_WAIT_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpGeocodingProvider.class);

    private final String name;
    private final String baseUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    protected AbstractHttpGeocodingProvider(RestClient restClient,
                                            ObjectMapper objectMapper,
                                            GeocodingProperties properties,
                                            String name) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.name = name;
        this.baseUrl = properties.providerConfig(name).baseUrl();
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Плейсхолдеры вида "null"/"undefined", которые сторонние API
     * отдают вместо незаполненного поля адреса.
     */
    protected static boolean isPlaceholder(String value) {
        return value.equalsIgnoreCase("null") || value.equalsIgnoreCase("undefined");
    }

    /** GET к провайдеру; Optional.empty — провайдер ответил «не найдено». */
    protected Optional<JsonNode> getJson(String path, MultiValueMap<String, String> params) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + path)
                .queryParams(params)
                .build()
                .encode()
                .toUri();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = restClient.get()
                        .uri(uri)
                        .retrieve()
                        .toEntity(String.class)
                        .getBody();
                if (!StringUtils.hasText(body)) {
                    return Optional.empty();
                }
                return Optional.of(parse(body, uri));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (HttpClientErrorException.TooManyRequests e) {
                long waitMillis = retryAfterMillis(e);
                if (waitMillis > MAX_INLINE_WAIT_MS) {
                    long seconds = Math.max(1, Duration.ofMillis(waitMillis).toSeconds());
                    throw new GeocodingUnavailableException(
                            "Провайдер '" + name + "': HTTP 429, сервер просит подождать "
                                    + seconds + " с — ожидание в потоке не блокируем, повторите позже ("
                                    + displayUri(uri) + ")", seconds);
                }
                retry(attempt, uri, "HTTP 429", waitMillis);
            } catch (HttpServerErrorException e) {
                retry(attempt, uri, "HTTP " + e.getStatusCode().value(), retryAfterMillis(e));
            } catch (HttpClientErrorException e) {
                throw new GeocodingProviderException(
                        "Провайдер '" + name + "' вернул HTTP " + e.getStatusCode().value()
                                + " (" + displayUri(uri) + "): " + e.getResponseBodyAsString(), e);
            } catch (RestClientException e) {
                retry(attempt, uri, e.getMessage(), 0);
            }
        }
        throw new GeocodingProviderException(
                "Провайдер '" + name + "': неожиданное завершение цикла ретраев (" + displayUri(uri) + ")");
    }

    private void retry(int attempt, URI uri, String reason, long retryAfterMillis) {
        if (attempt >= MAX_ATTEMPTS) {
            throw new GeocodingProviderException(
                    "Провайдер '" + name + "': " + reason + " после " + MAX_ATTEMPTS + " попыток ("
                            + displayUri(uri) + ")");
        }
        long delay = retryAfterMillis > 0 ? retryAfterMillis : BACKOFF_BASE_MS << (attempt - 1);
        log.warn("Провайдер '{}' вернул '{}', попытка {}/{}, повтор через {} мс ({})",
                name, reason, attempt, MAX_ATTEMPTS, delay, displayUri(uri));
        sleep(delay);
    }

    /**
     * Retry-After: секунды (число), HTTP-date (RFC 1123) либо ISO-8601; иначе 0 → backoff.
     */
    private long retryAfterMillis(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(header)) {
            return 0;
        }
        String value = header.trim();
        try {
            return Math.max(0, Long.parseLong(value)) * 1000L;
        } catch (NumberFormatException ignored) {
            // не секунды — пробуем форматы даты
        }
        try {
            ZonedDateTime target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(ZoneOffset.UTC);
            return Math.max(0, Duration.between(ZonedDateTime.now(ZoneOffset.UTC), target).toMillis());
        } catch (DateTimeParseException ignored) {
            // не RFC 1123 — пробуем ISO-8601
        }
        try {
            return Math.max(0, Duration.between(Instant.now(), Instant.parse(value)).toMillis());
        } catch (DateTimeParseException ex) {
            return 0;
        }
    }

    /** URI без query — не логируем пользовательский адрес. */
    private String displayUri(URI uri) {
        String value = uri.toString();
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodingProviderException("Ожидание повтора у провайдера '" + name + "' прервано", e);
        }
    }

    private JsonNode parse(String body, URI uri) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new GeocodingProviderException(
                    "Провайдер '" + name + "': не удалось разобрать JSON (" + displayUri(uri) + ")", e);
        }
    }
}
