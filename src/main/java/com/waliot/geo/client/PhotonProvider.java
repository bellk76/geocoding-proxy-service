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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Komoot Photon: GET /api/ (прямое) и GET /reverse (обратное).
 * Ответ — FeatureCollection, адрес собирается из properties (name, street, city, state, country).
 */
@Component
public class PhotonProvider extends AbstractHttpGeocodingProvider {

    private static final String NAME = "photon";

    public PhotonProvider(RestClient geocodingRestClient,
                          ObjectMapper objectMapper,
                          GeocodingProperties properties) {
        super(geocodingRestClient, objectMapper, properties, NAME);
    }

    @Override
    public Optional<GeocodingResult> geocode(String address) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", address);
        params.add("limit", "1");
        return getJson("/api/", params).flatMap(this::toResult);
    }

    @Override
    public Optional<GeocodingResult> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("lat", latitude.toPlainString());
        params.add("lon", longitude.toPlainString());
        return getJson("/reverse", params).flatMap(this::toResult);
    }

    private Optional<GeocodingResult> toResult(JsonNode node) {
        JsonNode features = node.path("features");
        if (!features.isArray() || features.isEmpty()) {
            return Optional.empty();
        }
        JsonNode feature = features.get(0);
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (coordinates.size() < 2
                || !coordinates.get(0).isNumber()
                || !coordinates.get(1).isNumber()) {
            throw new GeocodingProviderException("Провайдер '" + NAME + "': в ответе нет координат");
        }
        String address = formatAddress(feature.path("properties"));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal latitude = coordinates.get(1).decimalValue();
        BigDecimal longitude = coordinates.get(0).decimalValue();
        return Optional.of(new GeocodingResult(latitude, longitude, address, NAME));
    }

    private String formatAddress(JsonNode properties) {
        Set<String> parts = new LinkedHashSet<>();
        add(parts, properties, "name");
        addStreetWithNumber(parts, properties);
        add(parts, properties, "locality");
        add(parts, properties, "district");
        add(parts, properties, "city");
        add(parts, properties, "state");
        add(parts, properties, "country");
        return String.join(", ", parts);
    }

    private void addStreetWithNumber(Set<String> parts, JsonNode properties) {
        String street = text(properties, "street");
        if (street.isEmpty()) {
            return;
        }
        String housenumber = text(properties, "housenumber");
        parts.add(housenumber.isEmpty() ? street : street + " " + housenumber);
    }

    private void add(Set<String> parts, JsonNode properties, String field) {
        String value = text(properties, field);
        if (!value.isEmpty()) {
            parts.add(value);
        }
    }

    private String text(JsonNode properties, String field) {
        JsonNode value = properties.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText("").trim();
        return isPlaceholder(text) ? "" : text;
    }
}
