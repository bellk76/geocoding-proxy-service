package com.waliot.geo.exception;

/**
 * Запрос не дал результатов → HTTP 404:
 * адрес не найден в прямом поиске, координаты — в обратном.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
