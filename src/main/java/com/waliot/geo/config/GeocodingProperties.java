package com.waliot.geo.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Настройки геокодирования: активный провайдер, таймауты HTTP,
 * лимит исходящих запросов, TTL кэша и адреса провайдеров.
 * Валидация обязательных полей — на старте приложения (fail-fast).
 */
@ConfigurationProperties(prefix = "geocoding")
@Validated
public record GeocodingProperties(
        @NotBlank(message = "Активный провайдер должен быть указан (geocoding.provider)")
        String provider,

        @NotBlank(message = "User-Agent обязателен для работы с внешними API (geocoding.user-agent)")
        String userAgent,

        @NotNull(message = "geocoding.connect-timeout должен быть задан")
        Duration connectTimeout,

        @NotNull(message = "geocoding.read-timeout должен быть задан")
        Duration readTimeout,

        @Min(value = 1, message = "Лимит параллельных запросов должен быть >= 1 (geocoding.max-concurrent-requests)")
        int maxConcurrentRequests,

        @NotNull(message = "geocoding.single-flight-timeout должен быть задан")
        Duration singleFlightTimeout,

        @NotNull(message = "Секция geocoding.cache должна быть задана")
        Cache cache,

        Map<String, Provider> providers) {

    public record Cache(
            @NotNull(message = "geocoding.cache.ttl должен быть задан")
            Duration ttl,
            @NotNull(message = "geocoding.cache.cleanup-interval должен быть задан")
            Duration cleanupInterval) {
    }

    public record Provider(String baseUrl) {
    }

    /**
     * Конфигурация провайдера по имени; пустой адрес — ошибка конфигурации на старте.
     */
    public Provider providerConfig(String name) {
        Provider config = providers == null ? null : providers.get(name);
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "Не задан geocoding.providers." + name + ".base-url в application.yml");
        }
        return config;
    }
}
