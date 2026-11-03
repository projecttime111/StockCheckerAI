package com.stockpilot.ai.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    @Test
    void genericErrorsKeepTheSafeExistingApiResponse() {
        var request = new MockHttpServletRequest("POST", "/api/auth/register");
        var response = new GlobalExceptionHandler().generic(new IllegalStateException("private database detail"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Something went wrong");
        assertThat(response.getBody().message()).doesNotContain("private database detail");
        assertThat(response.getBody().path()).isEqualTo("/api/auth/register");
    }
}
