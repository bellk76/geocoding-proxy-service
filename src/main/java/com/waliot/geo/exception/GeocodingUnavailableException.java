package com.waliot.geo.exception;

/**
 * Временная недоступность геокодирования → HTTP 503.
 * Возникает, когда провайдер просит подождать дольше инлайн-лимита (429
 * с длительным Retry-After) или истёк лимит ожидания параллельного запроса.
 */
public class GeocodingUnavailableException extends GeocodingProviderException {

    private final Long retryAfterSeconds;

    public GeocodingUnavailableException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Значение для заголовка Retry-After (секунды), либо null.
     */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
