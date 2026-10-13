package com.waliot.geo.service;

import com.waliot.geo.TestFixtures;
import com.waliot.geo.client.GeocodingProvider;
import com.waliot.geo.config.GeocodingProperties;
import com.waliot.geo.dto.GeocodingResult;
import com.waliot.geo.entity.QueryType;
import com.waliot.geo.exception.GeocodingProviderException;
import com.waliot.geo.repository.GeocodeCacheRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сервис: кэш-first, нормализация ключей, single-flight, семафор, метрики.
 * Провайдер и репозиторий — Mockito; БД и сеть не участвуют.
 */
@ExtendWith(MockitoExtension.class)
class GeocodingServiceTest {

    @Mock
    private GeocodeCacheRepository repository;

    @Mock
    private GeocodingProvider provider;

    private SimpleMeterRegistry meterRegistry;
    private GeocodingService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new GeocodingService(repository, List.of(provider), TestFixtures.properties(), meterRegistry);
        lenient().when(provider.name()).thenReturn("nominatim");
    }

    @Test
    void cacheHitDoesNotCallProvider() {
        when(repository.findByQueryKeyAndQueryType("москва", QueryType.FORWARD))
                .thenReturn(Optional.of(TestFixtures.row("москва", QueryType.FORWARD,
                        OffsetDateTime.now().plusHours(1))));

        Optional<GeocodingResult> result = service.geocode("  Москва  ");

        assertThat(result).map(GeocodingResult::formattedAddress).contains(TestFixtures.TEST_RESULT_ADDRESS);
        verify(provider, never()).geocode(anyString());
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), any());
        assertThat(requests("forward", "cache_hit")).isEqualTo(1.0);
    }

    @Test
    void cacheMissFetchesProviderAndWritesCacheWithNormalizedKey() {
        when(repository.findByQueryKeyAndQueryType("москва", QueryType.FORWARD))
                .thenReturn(Optional.empty());
        when(provider.geocode("Москва")).thenReturn(Optional.of(TestFixtures.result()));

        Optional<GeocodingResult> result = service.geocode("Москва");

        assertThat(result).isPresent();
        ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).upsert(eq("москва"), eq("FORWARD"),
                eq(new BigDecimal("55.7558")), eq(new BigDecimal("37.6173")),
                eq(TestFixtures.TEST_RESULT_ADDRESS), eq("nominatim"), expiresCaptor.capture());
        assertThat(expiresCaptor.getValue())
                .isBetween(OffsetDateTime.now().plusMinutes(59), OffsetDateTime.now().plusMinutes(61));
        assertThat(requests("forward", "provider_hit")).isEqualTo(1.0);
    }

    @Test
    void staleCacheEntryIsRefreshedViaUpsert() {
        when(repository.findByQueryKeyAndQueryType("москва", QueryType.FORWARD))
                .thenReturn(Optional.of(TestFixtures.row("москва", QueryType.FORWARD,
                        OffsetDateTime.now().minusMinutes(1))));
        when(provider.geocode("Москва")).thenReturn(Optional.of(TestFixtures.result()));

        Optional<GeocodingResult> result = service.geocode("Москва");

        assertThat(result).isPresent();
        verify(provider).geocode("Москва");
        verify(repository).upsert(eq("москва"), eq("FORWARD"), any(), any(), any(), any(), any());
    }

    @Test
    void emptyProviderResultIsNotCached() {
        when(repository.findByQueryKeyAndQueryType(anyString(), eq(QueryType.FORWARD)))
                .thenReturn(Optional.empty());
        when(provider.geocode(anyString())).thenReturn(Optional.empty());

        assertThat(service.geocode("Nowhere")).isEmpty();
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), any());
        assertThat(requests("forward", "not_found")).isEqualTo(1.0);
    }

    @Test
    void reverseKeyRoundsCoordinatesToFiveDecimals() {
        when(repository.findByQueryKeyAndQueryType(anyString(), eq(QueryType.REVERSE)))
                .thenReturn(Optional.empty());
        when(provider.reverseGeocode(any(), any())).thenReturn(Optional.of(TestFixtures.result()));

        service.reverseGeocode(new BigDecimal("0.60000"), new BigDecimal("1.0"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).findByQueryKeyAndQueryType(keyCaptor.capture(), eq(QueryType.REVERSE));
        assertThat(keyCaptor.getValue()).isEqualTo("0.6,1");
    }

    @Test
    void providerErrorIsCountedAndRethrown() {
        when(repository.findByQueryKeyAndQueryType(anyString(), eq(QueryType.FORWARD)))
                .thenReturn(Optional.empty());
        when(provider.geocode(anyString())).thenThrow(new GeocodingProviderException("boom"));

        assertThatThrownBy(() -> service.geocode("Москва"))
                .isInstanceOf(GeocodingProviderException.class);

        assertThat(requests("forward", "error")).isEqualTo(1.0);
    }

    @Test
    void concurrentSameKeyCallsProviderOnce() throws Exception {
        when(repository.findByQueryKeyAndQueryType(anyString(), eq(QueryType.FORWARD)))
                .thenReturn(Optional.empty());
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.geocode("x")).thenAnswer(invocation -> {
            inProvider.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(TestFixtures.result());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<GeocodingResult>> first = pool.submit(() -> service.geocode("x"));
            assertThat(inProvider.await(2, TimeUnit.SECONDS)).isTrue();
            Future<Optional<GeocodingResult>> second = pool.submit(() -> service.geocode("x"));
            awaitSingleFlightWaiter();
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
            verify(provider, times(1)).geocode("x");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Ждём, пока второй поток войдёт в ожидание single-flight (вместо фиксированного sleep). */
    private void awaitSingleFlightWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.waitingInSingleFlight() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(service.waitingInSingleFlight()).isEqualTo(1);
    }

    @Test
    void semaphoreLimitsConcurrentProviderCalls() throws Exception {
        service = new GeocodingService(repository, List.of(provider),
                TestFixtures.properties(1), meterRegistry);
        when(repository.findByQueryKeyAndQueryType(anyString(), any()))
                .thenReturn(Optional.empty());
        AtomicInteger inProvider = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        when(provider.geocode(anyString())).thenAnswer(invocation -> {
            start.await(2, TimeUnit.SECONDS);
            maxSeen.accumulateAndGet(inProvider.incrementAndGet(), Math::max);
            Thread.sleep(300);
            inProvider.decrementAndGet();
            return Optional.of(TestFixtures.result());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<GeocodingResult>> first = pool.submit(() -> service.geocode("a"));
            Future<Optional<GeocodingResult>> second = pool.submit(() -> service.geocode("b"));
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(maxSeen.get()).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private double requests(String type, String result) {
        var counter = meterRegistry.find("geocode.requests").tags("type", type, "result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
