-- Кэш геокодирования: прямое (адрес -> координаты) и обратное (координаты -> адрес).
-- Хранятся только нормализованные данные, сырой JSON провайдеров не сохраняется.

CREATE TABLE geocode_cache (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    query_key         VARCHAR(512)   NOT NULL,
    query_type        VARCHAR(16)    NOT NULL,
    latitude          NUMERIC(9, 6)  NOT NULL,
    longitude         NUMERIC(9, 6)  NOT NULL,
    formatted_address TEXT           NOT NULL,
    provider          VARCHAR(32)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_geocode_cache_key_type UNIQUE (query_key, query_type),
    CONSTRAINT ck_geocode_cache_query_type CHECK (query_type IN ('FORWARD', 'REVERSE')),
    CONSTRAINT ck_geocode_cache_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_geocode_cache_longitude CHECK (longitude BETWEEN -180 AND 180)
);

-- Составной UNIQUE (query_key, query_type) изолирует пространства ключей FORWARD и REVERSE.
-- Индекс по expires_at — периодическая очистка устаревших записей (TTL).
CREATE INDEX idx_geocode_cache_expires_at ON geocode_cache (expires_at);
