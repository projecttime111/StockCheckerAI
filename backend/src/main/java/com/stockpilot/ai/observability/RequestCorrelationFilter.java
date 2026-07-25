package com.stockpilot.ai.observability;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String RENDER_REQUEST_ID_HEADER = "Rndr-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var requestId = resolveRequestId(request);
        var startedAt = System.nanoTime();

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (shouldLogRequest(request)) {
                var durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .log(
                        "HTTP request completed: {} {} -> {} in {} ms",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        durationMs
                    );
            }
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        var renderRequestId = request.getHeader(RENDER_REQUEST_ID_HEADER);
        if (isSafeRequestId(renderRequestId)) {
            return renderRequestId;
        }

        var clientRequestId = request.getHeader(REQUEST_ID_HEADER);
        if (isSafeRequestId(clientRequestId)) {
            return clientRequestId;
        }

        return UUID.randomUUID().toString();
    }

    private boolean isSafeRequestId(String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches();
    }

    private boolean shouldLogRequest(HttpServletRequest request) {
        return !"OPTIONS".equals(request.getMethod()) && !request.getRequestURI().startsWith("/actuator/health");
    }
}
