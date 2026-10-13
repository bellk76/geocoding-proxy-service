package com.waliot.geo.entity;

/**
 * Тип запроса геокодирования.
 * FORWARD — адрес → координаты, REVERSE — координаты → адрес.
 */
public enum QueryType {
    FORWARD,
    REVERSE
}
