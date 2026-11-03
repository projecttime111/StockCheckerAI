package com.stockpilot.ai.exception;

import com.stockpilot.ai.observability.RequestCorrelationFilter;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> api(ApiException ex, HttpServletRequest request) {
        return build(ex.status, ex.errorCode, ex.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fields, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraint(ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), Map.of(), request);
    }

    @ExceptionHandler({BadCredentialsException.class})
    ResponseEntity<ApiErrorResponse> credentials(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password", Map.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> denied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", Map.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "UPLOAD_TOO_LARGE",
            "Upload file is too large. Increase UPLOAD_MAX_FILE_SIZE / UPLOAD_MAX_REQUEST_SIZE or split the Tally export.",
            Map.of(),
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> generic(Exception ex, HttpServletRequest request) {
        var requestId = currentRequestId(request);
        log.error(
            "Unhandled exception requestId={} method={} path={}",
            requestId,
            request.getMethod(),
            request.getRequestURI(),
            ex
        );
        reportToSentry(ex, request, requestId);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", Map.of(), request);
    }

    private void reportToSentry(Exception ex, HttpServletRequest request, String requestId) {
        try {
            Sentry.withScope(scope -> {
                scope.setTag("request_id", requestId);
                scope.setTag("http.method", request.getMethod());
                Sentry.captureException(ex);
            });
        } catch (RuntimeException reportingFailure) {
            log.warn("Sentry reporting failed for requestId={}", requestId, reportingFailure);
        }
    }

    private String currentRequestId(HttpServletRequest request) {
        var requestId = MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY);
        if (requestId != null) {
            return requestId;
        }
        var requestAttribute = request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        return requestAttribute == null ? "unavailable" : requestAttribute.toString();
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String error, String message, Map<String, String> fields, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
            Instant.now(),
            status.value(),
            error,
            message,
            fields,
            request.getRequestURI()
        ));
    }
}
