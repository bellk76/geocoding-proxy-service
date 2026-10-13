# geocoding-proxy-service

Кэширующий прокси-сервис прямого и обратного геокодирования.
Задача: [backend-1.md](https://github.com/waliot/test-tasks/blob/master/tasks/backend-1.md).

Сервис принимает HTTP-запросы с адресом или координатами, отдаёт результат от
внешнего провайдера (Nominatim или Photon) и кэширует его в PostgreSQL.
Повторные запросы отдаются из кэша без обращения к внешнему API.

## Возможности

- Прямое геокодирование (адрес → координаты) и обратное (координаты → адрес).
- Провайдеры без API-ключей: Nominatim (по умолчанию) и Photon, переключение одной переменной.
- Кэш в PostgreSQL: TTL 7 дней по умолчанию, фоновая очистка устаревших записей.
- Single-flight: при промахе кэша по одному ключу в сеть уходит один поток,
  остальные ждут его результата.
- Bulkhead (семафор) на одновременные вызовы провайдера.
- Ретраи с экспоненциальным backoff, уважение HTTP 429 + Retry-After
  (инлайн-ожидание ≤ 1 с, иначе быстрый отказ 503).
- Ошибки в формате RFC 7807 (Problem Detail).
- requestId в каждом логе (MDC, заголовок `X-Request-Id`), access-log.
- Метрики: счётчик запросов и таймер вызовов провайдера (Actuator `/actuator/metrics`).
- Docker-образ и docker-compose: сервис + PostgreSQL.

## Стек

Java 21 · Spring Boot 3.3.5 · Maven (wrapper) · PostgreSQL 16 · Flyway ·
Spring Boot Actuator · Testcontainers (юнит — JUnit 5 + Mockito).

## Быстрый старт

### Вариант 1. Docker / Podman Compose (рекомендуется)

```bash
docker compose up --build
# приложение: http://localhost:8080
# БД опубликована на 5433 (5432 может занимать локальный PostgreSQL)
```

Порт приложения на хосте настраивается через `APP_PORT` (по умолчанию 8080):

```bash
APP_PORT=8081 docker compose up --build
```

> На машине с Podman: `docker` в этих командах — CLI Podman
> (`podman machine start`; в rootless-режиме проброс портов работает через
> gvisor-tap-vsock). Ключевые переменные compose: `GEOCODING_PROVIDER`,
> `GEOCODING_USER_AGENT`, `LOG_LEVEL`.

### Вариант 2. Локальная сборка

Нужны JDK 21 (версию закрепляет `maven-toolchains-plugin`, temurin) и запущенная
PostgreSQL (например, `docker run -e POSTGRES_DB=geocoding_db -e POSTGRES_USER=geo
-e POSTGRES_PASSWORD=geo -p 5432:5432 postgres:16-alpine`):

```bash
./mvnw spring-boot:run     # или ./mvnw package && java -jar target/*.jar
```

Схему накатывает Flyway автоматически при старте.

## API

Базовый путь: `/api/v1`. Типичные заголовки ответа: `Content-Type: application/json`.

### Прямое геокодирование

```
GET /api/v1/geocode?address={адрес}
```

- `address` — обязателен, непустой, максимум 512 символов.

```bash
curl 'http://localhost:8080/api/v1/geocode?address=Moscow'
```

```json
{
  "latitude": 55.6255780,
  "longitude": 37.6063916,
  "formattedAddress": "Москва, Центральный федеральный округ, Россия",
  "provider": "nominatim"
}
```

### Обратное геокодирование

```
GET /api/v1/reverse?lat={широта}&lon={долгота}
```

- `lat` — обязателен, [-90; 90]; `lon` — обязателен, [-180; 180].

```bash
curl 'http://localhost:8080/api/v1/reverse?lat=55.7558&lon=37.6173'
```

Ответ — тот же объект `GeocodingResult`.

### Коды ошибок

| Статус | Когда                                                      |
|--------|------------------------------------------------------------|
| 400    | Невалидные параметры (пустой/длинный адрес, координаты вне диапазона) |
| 404    | Провайдер не нашёл результат (адрес или координаты не найдены) |
| 405    | Метод не поддерживается (только GET)                        |
| 502    | Провайдер вернул ошибку/неразборчивый ответ                 |
| 503    | Провайдер недоступен или отвечает дольше допустимого (заголовок `Retry-After`) |

Тело ошибки — Problem Detail (RFC 7807), например:

```json
{
  "type": "urn:geocoding-proxy:problem:404",
  "title": "Запрос не дал результатов",
  "status": 404,
  "detail": "Адрес не найден: Nowhere-12345"
}
```

### Health и метрики

```
GET /actuator/health    → {"status":"UP"}
GET /actuator/metrics   → список метрик
GET /actuator/metrics/geocode.requests
GET /actuator/metrics/geocode.provider
```

## Кэш

- Ключ FORWARD: адрес, `trim` + `toLowerCase(Locale.ROOT)` + схлопывание пробелов.
- Ключ REVERSE: координаты с округлением до 5 знаков (`setScale(5, HALF_UP)`),
  ключ формируется с точкой, сам результат возвращается без округления.
- TTL по умолчанию 7 дней (`geocoding.cache.ttl`), устаревшие записи удаляет
  фоновая задача раз в час (`geocoding.cache.cleanup-interval`).
- Пустые результаты провайдера в кэш не сохраняются (404 всегда идёт в провайдер).
- Ключи длиннее 512 символов не кэшируются, запрос обходится кэшем.

## Конфигурация

Переменные окружения (значения по умолчанию — в `application.yml`):

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `PORT` | Порт приложения | `8080` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL | `localhost/5432/geocoding_db/geo/geo` |
| `DB_URL` | Полный JDBC URL (переопределяет `DB_*`) | — |
| `GEOCODING_PROVIDER` | `nominatim` или `photon` | `nominatim` |
| `GEOCODING_USER_AGENT` | User-Agent для провайдера (для Nominatim укажите контактный e-mail по usage policy) | `geocoding-proxy-service/0.0.1` |
| `GEOCODING_MAX_CONCURRENT` | Bulkhead: одновременных вызовов провайдера | `4` |
| `SINGLE_FLIGHT_TIMEOUT` | Верхний предел ожидания single-flight (ISO-8601) | `PT60S` |
| `CACHE_TTL` | TTL записи кэша (ISO-8601) | `PT168H` (7 дней) |
| `CACHE_CLEANUP_INTERVAL` | Период очистки кэша (ISO-8601) | `PT1H` |
| `NOMINATIM_URL`, `PHOTON_URL` | Базовые URL провайдеров | официальные |
| `LOG_LEVEL` | Уровень логирования `com.waliot.geo` | `INFO` |

Таймауты HTTP-клиента (`connect-timeout: 5s`, `read-timeout: 10s`) задаются
в `application.yml`.

> Креденшелы по умолчанию `geo`/`geo` — только для локальной разработки.
> В проде обязательно переопределяйте `DB_USER`/`DB_PASSWORD`.

## Метрики

- `geocode.requests` (Counter) — теги: `type` = `forward`/`reverse`,
  `result` = `cache_hit`/`provider_hit`/`not_found`/`error`.
  Считает **исход каждого запроса**, включая ожидающих в очереди single-flight
  (они получают тот же исход, что и инициатор).
- `geocode.provider` (Timer) — тег `provider`, перцентили p50/p95/p99;
  измеряет только вызов внешнего API (без ожидания семафора), поэтому его count —
  это число реальных походов к провайдеру.

> Для публичного сервиса закройте `/actuator/metrics` и `/actuator/health`
> аутентификацией (см. `management.endpoints.web.exposure`) — сейчас они открыты
> по умолчанию для удобства локальной проверки.

## Логирование

- Каждый запрос получает `requestId`: из заголовка `X-Request-Id`
  (валидируется `[A-Za-z0-9._-]{1,64}`), иначе генерируется UUID.
- `requestId` попадает в паттерн логов (`[req:...]`) и во все сообщения запроса.
- Access-log на INFO (кроме `/actuator/**` — там DEBUG).
- Сбои провайдеров — WARN (причина в сообщении, стек на DEBUG);
  непредвиденные ошибки (`500`) — ERROR со стеком.

## Тесты

```bash
./mvnw test
```

29 тестов: юнит (провайдеры с MockWebServer, сервис) + интеграционные
(живая PostgreSQL через Testcontainers, Flyway, реальный HTTP-контур).

Особенности окружения Podman (WSL2, Windows):

- Ryuk отключён (`TESTCONTAINERS_RYUK_DISABLED=true` в surefire) —
  контейнеры чистит shutdown hook Testcontainers.
- Готовность PostgreSQL ждётся по TCP-порту (`Wait.forListeningPort` в
  `AbstractIntegrationTest`): named-pipe Windows при rootful Podman не стримит
  логи docker-java. Страховка от гонки «порт открылся раньше, чем БД готова
  принимать соединения» — `initialization-fail-timeout` HikariCP через
  `@DynamicPropertySource` в `AbstractIntegrationTest`.
- Подключение Testcontainers к Podman задаётся машинно-локально в
  `~/.testcontainers.properties` (`docker.host=npipe:////./pipe/docker_engine`);
  файл не коммитится, на машине с Docker не нужен.

## Сборка образа

```bash
docker build -t geocoding-proxy-service .
docker run -p 8080:8080 -e DB_HOST=... geocoding-proxy-service
```

Образ двухфазный: сборка в `maven:3.9-eclipse-temurin-21` (слой зависимостей
кэшируется), запуск в `eclipse-temurin:21-jre` от непривилегированного
пользователя `app`. Тесты в образ не входят (интеграционным нужен Podman/Docker).

## Структура

```
src/main/java/com/waliot/geo/
├── controller   REST-контроллер, валидация параметров
├── service      бизнес-логика: кэш, single-flight, семафор, метрики, очистка
├── repository   JPA-репозиторий кэша
├── client       провайдеры (Nominatim, Photon), ретраи, разбор ответов
├── config       HTTP-клиент, фильтр requestId/access-log, свойства
├── dto          GeocodingResult и прочие DTO (records)
├── entity       GeocodeCache (JPA)
└── exception    исключения + GlobalExceptionHandler (Problem Detail)
src/main/resources/db/migration   Flyway-миграции
src/test/java/com/waliot/geo      юнит- и интеграционные тесты
```
