package com.waliot.geo.service;

import com.waliot.geo.client.GeocodingProvider;
import com.waliot.geo.config.GeocodingProperties;
import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.entity.GeocodeCache;
import com.waliot.geo.entity.QueryType;
import com.waliot.geo.exception.GeocodingProviderException;
import com.waliot.geo.exception.GeocodingUnavailableException;
import com.waliot.geo.repository.GeocodeCacheRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Геокодирование: кэш → single-flight → семафор → провайдер → запись в кэш с TTL.
 */
@Service
public class GeocodingService {

    /** Лимит ключа кэша совпадает с VARCHAR(512) колонки query_key. */
    private static final int MAX_CACHE_KEY_LENGTH = 512;

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private final GeocodeCacheRepository repository;
    private final List<GeocodingProvider> providers;
    private final GeocodingProperties properties;
    private final MeterRegistry meterRegistry;

    /** Ограничивает число одновременных исходящих вызовов к провайдеру. */
    private final Semaphore providerSemaphore;

    /** Предзарегистрированные счётчики исходов (горячий путь — без сборки метра на каждый вызов). */
    private final Map<String, Counter> requestCounters;

    private final ConcurrentHashMap<String, Timer> providerTimers = new ConcurrentHashMap<>();

    private final AtomicInteger singleFlightWaiters = new AtomicInteger();

    /** Незавершённые походы к провайдеру по ключу (single-flight). */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<GeocodingResult>>> inFlight =
            new ConcurrentHashMap<>();

    public GeocodingService(GeocodeCacheRepository repository,
                            List<GeocodingProvider> providers,
                            GeocodingProperties properties,
                            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.providers = providers;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.providerSemaphore = new Semaphore(properties.maxConcurrentRequests(), true);
        this.requestCounters = buildRequestCounters(meterRegistry);
    }

    /**
     * Прямое геокодирование: адрес → координаты.
     */
    public Optional<GeocodingResult> geocode(String address) {
        String key = normalizeKey(address);
        String query = address.trim();
        return resolve(key, QueryType.FORWARD, provider -> provider.geocode(query));
    }

    /**
     * Обратное геокодирование: координаты → адрес. Ключ округляется до 5 знаков,
     * результат сохраняется с полной точностью.
     */
    public Optional<GeocodingResult> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {
        String key = latitude.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                + ","
                + longitude.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return resolve(key, QueryType.REVERSE,
                provider -> provider.reverseGeocode(latitude, longitude));
    }

    private Optional<GeocodingResult> resolve(String key, QueryType type,
                                              Function<GeocodingProvider, Optional<GeocodingResult>> call) {
        String typeName = type.name().toLowerCase(Locale.ROOT);
        boolean cacheable = key.length() <= MAX_CACHE_KEY_LENGTH;
        try {
            GeocodingProvider provider = activeProvider();
            if (cacheable) {
                Optional<GeocodingResult> cached = readCache(key, type);
                if (cached.isPresent()) {
                    log.debug("Кэш: попадание {} '{}'", type, key);
                    countRequests(typeName, "cache_hit");
                    return cached;
                }
                log.debug("Кэш: промах {} '{}'", type, key);
            }

            Optional<GeocodingResult> result = singleFlight(flightKey(key, type), () -> {
                Optional<GeocodingResult> fetched = withSemaphore(provider, type, key,
                        () -> call.apply(provider));
                if (cacheable && fetched.isPresent()) {
                    writeCache(key, type, fetched.get());
                }
                return fetched;
            });
            if (result.isEmpty()) {
                log.debug("Провайдер не нашёл результат {} '{}'", type, key);
                countRequests(typeName, "not_found");
            } else {
                countRequests(typeName, "provider_hit");
            }
            return result;
        } catch (RuntimeException e) {
            countRequests(typeName, "error");
            throw e;
        }
    }

    private Optional<GeocodingResult> withSemaphore(GeocodingProvider provider, QueryType type, String key,
                                                    Supplier<Optional<GeocodingResult>> call) {
        try {
            providerSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodingProviderException("Ожидание слота провайдера прервано", e);
        }
        try {
            log.debug("Провайдер '{}': вызов {} '{}'", provider.name(), type, key);
            return providerTimer(provider.name()).record(call);
        } finally {
            providerSemaphore.release();
        }
    }

    private void countRequests(String type, String result) {
        requestCounters.get(type + ":" + result).increment();
    }

    private Timer providerTimer(String providerName) {
        return providerTimers.computeIfAbsent(providerName, name -> Timer.builder("geocode.provider")
                .description("Длительность вызова провайдера (с ретраями)")
                .tag("provider", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    private static Map<String, Counter> buildRequestCounters(MeterRegistry registry) {
        Map<String, Counter> counters = new HashMap<>();
        for (String type : List.of("forward", "reverse")) {
            for (String result : List.of("cache_hit", "provider_hit", "not_found", "error")) {
                Counter counter = Counter.builder("geocode.requests")
                        .description("Запросы геокодирования: тип и исход")
                        .tag("type", type)
                        .tag("result", result)
                        .register(registry);
                counters.put(type + ":" + result, counter);
            }
        }
        return Map.copyOf(counters);
    }

    /** Первый поток по ключу исполняет загрузку, параллельные ждут его результат. */
    private Optional<GeocodingResult> singleFlight(String flightKey,
                                                   Supplier<Optional<GeocodingResult>> loader) {
        CompletableFuture<Optional<GeocodingResult>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<GeocodingResult>> existing = inFlight.putIfAbsent(flightKey, mine);
        if (existing != null) {
            log.debug("Ожидание результата параллельного запроса '{}'", flightKey);
            return await(existing);
        }
        try {
            Optional<GeocodingResult> result = loader.get();
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(flightKey, mine);
        }
    }

    private Optional<GeocodingResult> await(CompletableFuture<Optional<GeocodingResult>> future) {
        singleFlightWaiters.incrementAndGet();
        try {
            return future.get(properties.singleFlightTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Инициатор продолжит работу и запишет результат в кэш — просто не ждём дольше лимита.
            throw new GeocodingUnavailableException(
                    "Ожидание параллельного запроса превысило "
                            + properties.singleFlightTimeout() + " — повторите позже", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodingProviderException("Ожидание параллельного геокодирования прервано", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new GeocodingProviderException("Параллельный запрос геокодирования завершился ошибкой", cause);
        } finally {
            singleFlightWaiters.decrementAndGet();
        }
    }

    /** Только для тестов: число потоков, ожидающих в очереди single-flight. */
    int waitingInSingleFlight() {
        return singleFlightWaiters.get();
    }

    private Optional<GeocodingResult> readCache(String key, QueryType type) {
        Optional<GeocodeCache> found = repository.findByQueryKeyAndQueryType(key, type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        GeocodeCache row = found.get();
        if (row.getExpiresAt().isAfter(OffsetDateTime.now())) {
            return Optional.of(new GeocodingResult(
                    row.getLatitude(), row.getLongitude(), row.getFormattedAddress(), row.getProvider()));
        }
        log.debug("Кэш: запись просрочена {} '{}'", type, key);
        return Optional.empty();
    }

    private void writeCache(String key, QueryType type, GeocodingResult result) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.cache().ttl());
        repository.upsert(key, type.name(), result.latitude(), result.longitude(),
                result.formattedAddress(), result.provider(), expiresAt);
        log.debug("Кэш: запись {} '{}' до {}", type, key, expiresAt);
    }

    private GeocodingProvider activeProvider() {
        String name = properties.provider();
        return providers.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Провайдер '" + name + "' не зарегистрирован; доступны: "
                                + providers.stream().map(GeocodingProvider::name).toList()));
    }

    private String normalizeKey(String address) {
        return address.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String flightKey(String key, QueryType type) {
        return type + ":" + key;
    }
}
