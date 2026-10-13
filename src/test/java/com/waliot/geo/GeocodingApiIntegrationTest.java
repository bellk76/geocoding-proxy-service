package com.waliot.geo;

import com.waliot.geo.client.GeocodingProvider;
import com.waliot.geo.dto.GeocodingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозной тест веб-слоя: MockMvc + реальная PostgreSQL (Testcontainers)
 * + реальный Flyway + реальный сервис/кэш. Провайдер — Mockito-заглушка:
 * внешние API в тестах не вызываются.
 */
@AutoConfigureMockMvc
class GeocodingApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Подменяем оба реальных провайдера (по типу их два — @MockBean требует один). */
    @MockBean(name = "nominatimProvider")
    private GeocodingProvider nominatimProvider;

    @MockBean(name = "photonProvider")
    private GeocodingProvider photonProvider;

    @BeforeEach
    void stubProvider() {
        lenient().when(nominatimProvider.name()).thenReturn("nominatim");
        lenient().when(photonProvider.name()).thenReturn("photon");
        lenient().when(nominatimProvider.geocode(anyString())).thenReturn(Optional.of(new GeocodingResult(
                new BigDecimal("55.7558"), new BigDecimal("37.6173"), "Moscow, Russia", "nominatim")));
        lenient().when(nominatimProvider.reverseGeocode(any(), any())).thenReturn(Optional.of(new GeocodingResult(
                new BigDecimal("55.7558"), new BigDecimal("37.6173"), "Moscow, Russia", "nominatim")));
    }

    @Test
    void geocodeReturnsResultAndSecondCallHitsCache() throws Exception {
        mockMvc.perform(get("/api/v1/geocode").param("address", "Moscow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(55.7558))
                .andExpect(jsonPath("$.longitude").value(37.6173))
                .andExpect(jsonPath("$.formattedAddress").value("Moscow, Russia"))
                .andExpect(jsonPath("$.provider").value("nominatim"));

        mockMvc.perform(get("/api/v1/geocode").param("address", "Moscow"))
                .andExpect(status().isOk());

        verify(nominatimProvider, times(1)).geocode("Moscow");
    }

    @Test
    void geocodeNotFoundReturnsProblemDetail() throws Exception {
        when(nominatimProvider.geocode("Atlantis")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/geocode").param("address", "Atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:geocoding-proxy:problem:404"))
                .andExpect(jsonPath("$.title").value("Запрос не дал результатов"));
    }

    @Test
    void geocodeBlankAddressReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/geocode").param("address", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void geocodeMissingAddressParamReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/geocode"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reverseMissingLonParamReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reverse").param("lat", "55.7558"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reverseOutOfRangeLatitudeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reverse").param("lat", "91").param("lon", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reverseReturnsResult() throws Exception {
        mockMvc.perform(get("/api/v1/reverse").param("lat", "55.7558").param("lon", "37.6173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formattedAddress").value("Moscow, Russia"));

        verify(nominatimProvider).reverseGeocode(new BigDecimal("55.7558"), new BigDecimal("37.6173"));
    }

    @Test
    void unknownPathReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound());
    }
}
