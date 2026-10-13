package com.waliot.geo.client;

import com.waliot.geo.TestFixtures;
import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.exception.GeocodingProviderException;
import com.waliot.geo.exception.GeocodingUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.ExpectedCount.times;

/**
 * Nominatim: парсинг, ретраи (5xx), Retry-After (429), пустые и ошибочные ответы.
 * HTTP имитируется MockRestServiceServer — реальный API не вызывается.
 */
class NominatimProviderTest {

    private static final String SEARCH_URL = "https://nominatim.test/search?q=Moscow&format=jsonv2&limit=1";
    private static final String REVERSE_URL = "https://nominatim.test/reverse?lat=0&lon=0&format=jsonv2";
    private static final String RESULT_JSON =
            """
            [{"lat":"55.7558","lon":"37.6173","display_name":"Moscow, Russia"}]
            """;

    private MockRestServiceServer server;
    private NominatimProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new NominatimProvider(
                builder.baseUrl("https://nominatim.test").build(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                TestFixtures.properties());
    }

    @Test
    void geocodeParsesFirstElementOfArray() {
        server.expect(requestTo(SEARCH_URL))
                .andRespond(withSuccess(RESULT_JSON, MediaType.APPLICATION_JSON));

        Optional<GeocodingResult> result = provider.geocode("Moscow");

        assertThat(result).contains(new GeocodingResult(
                new BigDecimal("55.7558"), new BigDecimal("37.6173"),
                "Moscow, Russia", "nominatim"));
    }

    @Test
    void geocodeEmptyArrayIsEmpty() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Moscow")).isEmpty();
    }

    @Test
    void geocodeServerErrorRetriesThreeTimesThenFails() {
        server.expect(times(3), requestTo(SEARCH_URL)).andRespond(withServerError());

        assertThat(catchThrowableOfType(() -> provider.geocode("Moscow"), GeocodingProviderException.class))
                .hasMessageContaining("после 3 попыток");
    }

    @Test
    void geocodeRecoversAfterTwoServerErrors() {
        server.expect(times(2), requestTo(SEARCH_URL)).andRespond(withServerError());
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(RESULT_JSON, MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Moscow")).isPresent();
    }

    @Test
    void geocodeNotFoundReturnsEmpty() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(provider.geocode("Moscow")).isEmpty();
    }

    @Test
    void geocodeInvalidJsonFails() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess("oops", MediaType.TEXT_PLAIN));

        assertThat(catchThrowableOfType(() -> provider.geocode("Moscow"), GeocodingProviderException.class))
                .hasMessageContaining("не удалось разобрать JSON");
    }

    @Test
    void rateLimitLongRetryAfterFailsFastWith503Signal() {
        server.expect(requestTo(SEARCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "3600"));

        GeocodingUnavailableException e = catchThrowableOfType(
                () -> provider.geocode("Moscow"), GeocodingUnavailableException.class);

        assertThat(e).isNotNull();
        assertThat(e.getRetryAfterSeconds()).isEqualTo(3600L);
        server.verify();
    }

    @Test
    void rateLimitShortRetryAfterRetriesInline() {
        server.expect(requestTo(SEARCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "1"));
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(RESULT_JSON, MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Moscow")).isPresent();
    }

    @Test
    void reverseWithErrorFieldIsEmpty() {
        server.expect(requestTo(REVERSE_URL)).andRespond(withSuccess(
                """
                {"error":{"code":301,"message":"Unable to geocode"}}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.reverseGeocode(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void reverseParsesObject() {
        server.expect(requestTo(REVERSE_URL)).andRespond(withSuccess(
                """
                {"lat":"55.7558","lon":"37.6173","display_name":"Moscow, Russia"}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.reverseGeocode(BigDecimal.ZERO, BigDecimal.ZERO))
                .contains(new GeocodingResult(
                        new BigDecimal("55.7558"), new BigDecimal("37.6173"),
                        "Moscow, Russia", "nominatim"));
    }
}
