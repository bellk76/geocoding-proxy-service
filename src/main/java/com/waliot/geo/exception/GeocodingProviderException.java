package com.waliot.geo.exception;

/**
 * Ошибка обращения к стороннему провайдеру геокодирования:
 * исчерпаны ретраи, недоступен сервис или невалидный ответ.
 */
public class GeocodingProviderException extends RuntimeException {

    public GeocodingProviderException(String message) {
        super(message);
    }

    public GeocodingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
