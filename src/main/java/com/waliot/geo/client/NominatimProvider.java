package com.waliot.geo.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waliot.geo.config.GeocodingProperties;
import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.exception.GeocodingProviderException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * OpenStreetMap Nominatim: GET /search (прямое) и GET /reverse (обратное),
 * формат jsonv2. Ответы — массив (search) или объект (reverse) с полями lat/lon/display_name.
 */
@Component
public class NominatimProvider extends AbstractHttpGeocodingProvider {

    private static final String NAME = "nominatim";

    public NominatimProvider(RestClient geocodingRestClient,
                             ObjectMapper objectMapper,
                             GeocodingProperties properties) {
        super(geocodingRestClient, objectMapper, properties, NAME);
    }

    @Override
    public Optional<GeocodingResult> geocode(String address) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", address);
        params.add("format", "jsonv2");
        params.add("limit", "1");
        return getJson("/search", params).flatMap(this::toResult);
    }

    @Override
    public Optional<GeocodingResult> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("lat", latitude.toPlainString());
        params.add("lon", longitude.toPlainString());
        params.add("format", "jsonv2");
        return getJson("/reverse", params).flatMap(this::toResult);
    }

    private Optional<GeocodingResult> toResult(JsonNode node) {
        if (node.has("error")) {
            return Optional.empty();
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return Optional.empty();
            }
            node = node.get(0);
        }
        String address = node.path("display_name").asText("").trim();
        if (address.isEmpty() || isPlaceholder(address)) {
            return Optional.empty();
        }
        if (!node.hasNonNull("lat") || !node.hasNonNull("lon")) {
            throw new GeocodingProviderException("Провайдер '" + NAME + "': в ответе нет координат");
        }
        try {
            BigDecimal latitude = new BigDecimal(node.get("lat").asText());
            BigDecimal longitude = new BigDecimal(node.get("lon").asText());
            return Optional.of(new GeocodingResult(latitude, longitude, address, NAME));
        } catch (NumberFormatException e) {
            throw new GeocodingProviderException(
                    "Провайдер '" + NAME + "': некорректные координаты в ответе", e);
        }
    }
}
