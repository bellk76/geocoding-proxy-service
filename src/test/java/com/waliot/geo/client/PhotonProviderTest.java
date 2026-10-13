package com.waliot.geo.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waliot.geo.TestFixtures;
import com.waliot.geo.dto.GeocodingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Photon: FeatureCollection, порядок координат [lon, lat] (RFC 7946),
 * сборка адреса из properties, фильтрация плейсхолдеров.
 */
class PhotonProviderTest {

    private static final String SEARCH_URL = "https://photon.test/api/?q=Berlin&limit=1";
    private static final String REVERSE_URL = "https://photon.test/reverse?lat=52.517037&lon=13.38886";
    private static final String FEATURE_JSON =
            """
            {"features":[
              {"geometry":{"coordinates":[13.38886,52.517037]},
               "properties":{"name":"Berlin","city":"Berlin","country":"Germany"}}
            ]}
            """;

    private MockRestServiceServer server;
    private PhotonProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new PhotonProvider(
                builder.baseUrl("https://photon.test").build(),
                new ObjectMapper(),
                TestFixtures.properties());
    }

    @Test
    void geocodeParsesCoordinatesInLonLatOrderAndDeduplicatesAddress() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(FEATURE_JSON, MediaType.APPLICATION_JSON));

        Optional<GeocodingResult> result = provider.geocode("Berlin");

        // coordinate[0] = долгота, coordinate[1] = широта; name == city → дедуп в LinkedHashSet
        assertThat(result).contains(new GeocodingResult(
                new BigDecimal("52.517037"), new BigDecimal("13.38886"),
                "Berlin, Germany", "photon"));
    }

    @Test
    void reverseUsesSameParser() {
        server.expect(requestTo(REVERSE_URL)).andRespond(withSuccess(FEATURE_JSON, MediaType.APPLICATION_JSON));

        assertThat(provider.reverseGeocode(
                        new BigDecimal("52.517037"), new BigDecimal("13.38886")))
                .contains(new GeocodingResult(
                        new BigDecimal("52.517037"), new BigDecimal("13.38886"),
                        "Berlin, Germany", "photon"));
    }

    @Test
    void emptyFeaturesIsEmpty() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(
                """
                {"features":[]}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Berlin")).isEmpty();
    }

    @Test
    void placeholderFieldsAreFilteredFromAddress() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(
                """
                {"features":[
                  {"geometry":{"coordinates":[1.0,2.0]},
                   "properties":{"name":"Spot","city":"null","country":"undefined"}}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Berlin"))
                .map(GeocodingResult::formattedAddress)
                .contains("Spot");
    }

    @Test
    void streetWithHousenumberJoined() {
        server.expect(requestTo(SEARCH_URL)).andRespond(withSuccess(
                """
                {"features":[
                  {"geometry":{"coordinates":[1.0,2.0]},
                   "properties":{"street":"Hauptstrasse","housenumber":"12","city":"Berlin"}}
                ]}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("Berlin"))
                .map(GeocodingResult::formattedAddress)
                .contains("Hauptstrasse 12, Berlin");
    }
}
