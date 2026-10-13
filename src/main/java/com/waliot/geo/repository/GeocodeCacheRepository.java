package com.waliot.geo.repository;

import com.waliot.geo.entity.GeocodeCache;
import com.waliot.geo.entity.QueryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Доступ к кэшу геокодирования. Составной ключ (query_key, query_type)
 * изолирует пространства прямого и обратного запросов.
 */
public interface GeocodeCacheRepository extends JpaRepository<GeocodeCache, Long> {

    Optional<GeocodeCache> findByQueryKeyAndQueryType(String queryKey, QueryType queryType);

    /**
     * Вставка с обновлением при конфликте уникального ключа (query_key, query_type):
     * протухшая запись перезаписывается сразу, без ожидания фоновой очистки.
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO geocode_cache (query_key, query_type, latitude, longitude,
                formatted_address, provider, expires_at)
            VALUES (:queryKey, :queryType, :latitude, :longitude,
                :formattedAddress, :provider, :expiresAt)
            ON CONFLICT (query_key, query_type) DO UPDATE SET
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                formatted_address = EXCLUDED.formatted_address,
                provider = EXCLUDED.provider,
                expires_at = EXCLUDED.expires_at
            """)
    void upsert(@Param("queryKey") String queryKey,
                @Param("queryType") String queryType,
                @Param("latitude") BigDecimal latitude,
                @Param("longitude") BigDecimal longitude,
                @Param("formattedAddress") String formattedAddress,
                @Param("provider") String provider,
                @Param("expiresAt") OffsetDateTime expiresAt);

    /**
     * Фоновая очистка устаревших записей (TTL) — см. CacheCleanupScheduler.
     */
    @Modifying
    @Transactional
    @Query("delete from GeocodeCache c where c.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
