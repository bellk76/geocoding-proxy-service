package com.waliot.geo.service;

import com.waliot.geo.repository.GeocodeCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** Периодически удаляет из кэша записи с истёкшим TTL. */
@Component
public class CacheCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CacheCleanupScheduler.class);

    private final GeocodeCacheRepository repository;

    public CacheCleanupScheduler(GeocodeCacheRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${geocoding.cache.cleanup-interval}")
    public void cleanupExpired() {
        int removed = repository.deleteExpired(OffsetDateTime.now());
        if (removed > 0) {
            log.info("Кэш: удалено устаревших записей: {}", removed);
        }
    }
}
