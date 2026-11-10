package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendLivenessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @MockBean(name="dbHealthContributor") HealthIndicator database;

    @BeforeEach void databaseIsUnavailable() {
        when(database.health()).thenReturn(Health.down()
            .withDetail("database", "private-database-name")
            .withDetail("connection", "private-database-connection").build());
        doCallRealMethod().when(database).getHealth(anyBoolean());
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        clearInvocations(database);
    }

    @AfterEach void restoreAvailability() {
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
    }

    @Test void publicLivenessStaysUpWhenDatabaseHealthIsDownAndExposesOnlyStatus() throws Exception {
        var live=mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(live.getContentAsString())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        assertThat(live.getHeader("Cache-Control")).contains("no-store");
        verifyNoInteractions(database);

        var aggregate=mvc.perform(get("/actuator/health"))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(aggregate).path("status").asText()).isEqualTo("DOWN");
        assertThat(aggregate).doesNotContain("private-database", "components", "details");
        verify(database).health();
    }

    @Test void expiredCredentialsDoNotContaminateLivenessButStillProtectBusinessRoutes() throws Exception {
        var live=mvc.perform(get("/actuator/health/liveness").header("Authorization", "Bearer invalid-expired-token"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(live)).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        mvc.perform(head("/actuator/health/liveness").header("Authorization", "Bearer invalid-expired-token"))
            .andExpect(status().isOk());
        verifyNoInteractions(database);
        mvc.perform(get("/api/products").header("Authorization", "Bearer invalid-expired-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test void brokenProcessLivenessReportsUnavailableWithoutDiagnosticDetails() throws Exception {
        AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
        var body=mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body)).isEqualTo(json.readTree("{\"status\":\"DOWN\"}"));
        verifyNoInteractions(database);
    }

    @Test void componentHealthIsNotPublicAndAllowedWebOriginCanProbeWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health/db")).andExpect(status().isForbidden());
        var response=mvc.perform(get("/actuator/health/liveness").header("Origin", "http://localhost:3000"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
    }
}
