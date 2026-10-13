package com.waliot.geo.client;

import com.waliot.geo.dto.GeocodingResult;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Сторонний сервис геокодирования. Реализации — в NominatimProvider, PhotonProvider.
 */
public interface GeocodingProvider {

    /** Имя провайдера — значение из application.yml (nominatim, photon). */
    String name();

    /** Прямое геокодирование: адрес → координаты. */
    Optional<GeocodingResult> geocode(String address);

    /** Обратное геокодирование: координаты → адрес. */
    Optional<GeocodingResult> reverseGeocode(BigDecimal latitude, BigDecimal longitude);
}
