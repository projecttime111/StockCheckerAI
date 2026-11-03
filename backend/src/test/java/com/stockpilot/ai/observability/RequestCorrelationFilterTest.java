package com.stockpilot.ai.observability;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void usesRenderRequestIdAndReturnsItToTheCaller() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Rndr-Id", "render-request-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("render-request-123");
        assertThat(request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("render-request-123");
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeClientRequestId() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "unsafe\nrequest-id");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)).isNull();
    }
}
