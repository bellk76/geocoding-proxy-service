package com.waliot.geo.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/**
 * Маппинг исключений в ProblemDetail (RFC 7807).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException e) {
        log.debug("Ресурс не найден: {}", e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Запрос не дал результатов", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail noSuchPath(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Ресурс не найден", "Такого пути не существует");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Метод не поддерживается", e.getMessage());
    }

    @ExceptionHandler(GeocodingUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(GeocodingUnavailableException e) {
        log.warn("Геокодирование временно недоступно: {}", e.getMessage());
        ProblemDetail detail = problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Геокодирование временно недоступно", e.getMessage());
        if (e.getRetryAfterSeconds() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(detail);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(detail);
    }

    @ExceptionHandler(GeocodingProviderException.class)
    public ProblemDetail badGateway(GeocodingProviderException e) {
        log.warn("Провайдер геокодирования вернул ошибку: {}", e.getMessage());
        log.debug("Детали сбоя провайдера", e);
        return problem(HttpStatus.BAD_GATEWAY, "Внешний геокодер недоступен", e.getMessage());
    }

    @ExceptionHandler({HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail badRequest(Exception e) {
        log.debug("Некорректные параметры запроса: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Некорректные параметры запроса", e.getMessage());
    }

    @ExceptionHandler(MissingRequestValueException.class)
    public ProblemDetail missingRequestValue(MissingRequestValueException e) {
        log.debug("Отсутствует обязательный параметр запроса: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Некорректные параметры запроса", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail internalError(Exception e) {
        log.error("Необработанная ошибка", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера", "Подробности — в логах сервера");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:geocoding-proxy:problem:" + status.value()));
        return problem;
    }
}
