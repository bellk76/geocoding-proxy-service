package com.waliot.geo.controller;

import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.exception.ResourceNotFoundException;
import com.waliot.geo.service.GeocodingService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** REST API геокодирования. Валидация параметров — Bean Validation на @RequestParam. */
@RestController
@Validated
@RequestMapping("/api/v1")
public class GeocodingController {

    /** Совпадает с VARCHAR(512) колонки query_key и лимитом кэша. */
    private static final int MAX_ADDRESS_LENGTH = 512;

    private final GeocodingService geocodingService;

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    /**
     * Прямое геокодирование: адрес → координаты.
     */
    @GetMapping("/geocode")
    public GeocodingResult geocode(
            @RequestParam("address") @NotBlank @Size(max = MAX_ADDRESS_LENGTH) String address) {
        return geocodingService.geocode(address)
                .orElseThrow(() -> new ResourceNotFoundException("Адрес не найден: " + address));
    }

    /**
     * Обратное геокодирование: координаты → адрес.
     */
    @GetMapping("/reverse")
    public GeocodingResult reverse(
            @RequestParam("lat")
            @DecimalMin(value = "-90", message = "Широта должна быть в диапазоне [-90, 90]")
            @DecimalMax(value = "90", message = "Широта должна быть в диапазоне [-90, 90]")
                    BigDecimal latitude,
            @RequestParam("lon")
            @DecimalMin(value = "-180", message = "Долгота должна быть в диапазоне [-180, 180]")
            @DecimalMax(value = "180", message = "Долгота должна быть в диапазоне [-180, 180]")
                    BigDecimal longitude) {
        return geocodingService.reverseGeocode(latitude, longitude)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Координаты не найдены: " + latitude + "," + longitude));
    }
}
