package com.waliot.geo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Запись кэша геокодирования: нормализованный результат, без сырого JSON провайдера. */
@Entity
@Table(
        name = "geocode_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_geocode_cache_key_type",
                columnNames = {"query_key", "query_type"}
        )
)
public class GeocodeCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "query_key", nullable = false, length = 512)
    private String queryKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_type", nullable = false, length = 16)
    private QueryType queryType;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "formatted_address", nullable = false, columnDefinition = "text")
    private String formattedAddress;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** Заполняется БД (default now()), из кода не записывается. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected GeocodeCache() {
    }

    public GeocodeCache(String queryKey,
                        QueryType queryType,
                        BigDecimal latitude,
                        BigDecimal longitude,
                        String formattedAddress,
                        String provider,
                        OffsetDateTime expiresAt) {
        this.queryKey = queryKey;
        this.queryType = queryType;
        this.latitude = latitude;
        this.longitude = longitude;
        this.formattedAddress = formattedAddress;
        this.provider = provider;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getQueryKey() {
        return queryKey;
    }

    public void setQueryKey(String queryKey) {
        this.queryKey = queryKey;
    }

    public QueryType getQueryType() {
        return queryType;
    }

    public void setQueryType(QueryType queryType) {
        this.queryType = queryType;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public String getFormattedAddress() {
        return formattedAddress;
    }

    public void setFormattedAddress(String formattedAddress) {
        this.formattedAddress = formattedAddress;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    @Override
    public String toString() {
        return "GeocodeCache{id=" + id
                + ", queryKey='" + queryKey + '\''
                + ", queryType=" + queryType
                + ", lat=" + latitude
                + ", lon=" + longitude
                + ", provider='" + provider + '\''
                + '}';
    }
}
