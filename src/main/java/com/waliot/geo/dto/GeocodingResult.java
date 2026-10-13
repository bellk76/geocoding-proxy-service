package com.waliot.geo.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Нормализованный результат геокодирования от провайдера —
 * единственное, что уходит в кэш и наружу в API.
 */
public record GeocodingResult(BigDecimal latitude,
                              BigDecimal longitude,
                              String formattedAddress,
                              String provider) {

    public GeocodingResult {
        Objects.requireNonNull(latitude, "Широта не может быть null");
        Objects.requireNonNull(longitude, "Долгота не может быть null");
        Objects.requireNonNull(formattedAddress, "Форматированный адрес не может быть null");
        Objects.requireNonNull(provider, "Имя провайдера не может быть null");

        if (formattedAddress.isBlank()) {
            throw new IllegalArgumentException("Форматированный адрес не должен быть пустым");
        }
    }
}
