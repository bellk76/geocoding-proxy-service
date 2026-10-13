package com.waliot.geo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

/**
 * База интеграционных тестов: живая PostgreSQL через Testcontainers
 * (движок — Podman, см. ~/.testcontainers.properties). @ServiceConnection
 * сам прокидывает datasource-свойства. Flyway прогоняет реальные миграции.
 * Ryuk отключён — контейнер останавливается по завершении тестового класса.
 * Wait — по TCP-порту, а не по логам: named-pipe Windows при rootful Podman
 * не стримит логи docker-java (обрыв соединения), а inspect/порты работают.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("geocoding_test")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

    /**
     * Страховка от гонки «порт открылся раньше, чем БД готова принимать соединения»:
     * Hikari не падает мгновенно, а ретраит получение первого соединения до 30 с.
     */
    @DynamicPropertySource
    static void hikariInitFailTimeout(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> "30000");
    }
}
