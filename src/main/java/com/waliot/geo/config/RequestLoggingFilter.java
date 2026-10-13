package com.waliot.geo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Пишет requestId в MDC (из заголовка X-Request-Id, иначе UUID)
 * и логирует каждый запрос.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_MDC = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.put(REQUEST_ID_MDC, resolveRequestId(request.getHeader(REQUEST_ID_HEADER)));
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            if (request.getRequestURI().startsWith("/actuator")) {
                log.debug("{} {} -> {} ({} мс)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), tookMs);
            } else {
                log.info("{} {} -> {} ({} мс)", request.getMethod(), fullUri(request),
                        response.getStatus(), tookMs);
            }
            MDC.remove(REQUEST_ID_MDC);
        }
    }

    /** Убираем управляющие символы из URI, чтобы они не попали в лог. */
    private String fullUri(HttpServletRequest request) {
        String query = request.getQueryString();
        String uri = request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
        return uri.replace('\n', '_').replace('\r', '_');
    }

    /** Валидируем присланный заголовок; иначе — собственный UUID. */
    private String resolveRequestId(String header) {
        if (header != null && REQUEST_ID_PATTERN.matcher(header.trim()).matches()) {
            return header.trim();
        }
        return UUID.randomUUID().toString();
    }
}
