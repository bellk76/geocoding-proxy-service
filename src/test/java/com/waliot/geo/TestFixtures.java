package com.waliot.geo;

import com.waliot.geo.config.GeocodingProperties;
import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.entity.GeocodeCache;
import com.waliot.geo.entity.QueryType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Общие фикстуры тестов: свойства конфигурации, результат геокодирования,
 * строка кэша. Базовые URL провайдеров указывают на тестовые домены —
 * реальные API в тестах не вызываются.
 */
public final class TestFixtures {

    public static final String TEST_RESULT_ADDRESS = "Moscow, Russia";

    private TestFixtures() {
    }

    public static GeocodingProperties properties() {
        return properties(4);
    }

    public static GeocodingProperties properties(int maxConcurrentRequests) {
        return new GeocodingProperties(
                "nominatim",
                "geocoding-proxy-service-test/0.0.1",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                maxConcurrentRequests,
                Duration.ofSeconds(10),
                new GeocodingProperties.Cache(Duration.ofHours(1), Duration.ofHours(1)),
                Map.of(
                        "nominatim", new GeocodingProperties.Provider("https://nominatim.test"),
                        "photon", new GeocodingProperties.Provider("https://photon.test")));
    }

    public static GeocodingResult result() {
        return new GeocodingResult(
                new BigDecimal("55.7558"), new BigDecimal("37.6173"),
                TEST_RESULT_ADDRESS, "nominatim");
    }

    public static GeocodeCache row(String queryKey,
                                   QueryType queryType,
                                   OffsetDateTime expiresAt) {
        return new GeocodeCache(
                queryKey, queryType,
                new BigDecimal("55.7558"), new BigDecimal("37.6173"),
                TEST_RESULT_ADDRESS, "nominatim", expiresAt);
    }
}
