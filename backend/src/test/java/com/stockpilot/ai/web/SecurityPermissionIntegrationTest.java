package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.DemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityPermissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.AuditLogRepository auditLogs;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @Test
    void warehouseStaffCannotAccessProfitAnalysis() throws Exception {
        var tenantId = registerTenant("profit-denied-" + System.nanoTime() + "@example.com");
        var token = tokenForRole(tenantId, DomainEnums.Role.WAREHOUSE_STAFF);

        mvc.perform(get("/api/insights/profit-drop").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesStaffCannotSeePurchaseCostThroughAi() throws Exception {
        var tenantId = registerTenant("sales-ai-denied-" + System.nanoTime() + "@example.com");
        var token = tokenForRole(tenantId, DomainEnums.Role.SALES_STAFF);

        mvc.perform(post("/api/ai/assistant/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Which supplier increased cost the most?\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffAiAssistantReturnsSafeAccessDeniedForProfitIntentAndAuditsDenial() throws Exception {
        var tenantId = registerTenant("staff-ai-denied-" + System.nanoTime() + "@example.com");
        var token = tokenForRole(tenantId, DomainEnums.Role.STAFF);

        var response = mapper.readTree(mvc.perform(post("/api/ai/assistant/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Why did my profit drop this month?\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        assertThat(response.get("response").asText()).isEqualTo("You do not have permission to access this information.");
        assertThat(response.get("evidence").size()).isZero();
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().anyMatch(row -> "AI_ACCESS_DENIED".equals(row.action))).isTrue();
    }

    @Test
    void viewerCannotExportReports() throws Exception {
        var tenantId = registerTenant("viewer-report-denied-" + System.nanoTime() + "@example.com");
        var token = tokenForRole(tenantId, DomainEnums.Role.VIEWER);

        mvc.perform(get("/api/reports/export/current-stock").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void integrationEndpointsRequireTallyIntegrationPermission() throws Exception {
        var tenantId = registerTenant("integration-denied-" + System.nanoTime() + "@example.com");
        var warehouseToken = tokenForRole(tenantId, DomainEnums.Role.WAREHOUSE_STAFF);
        var auditorToken = tokenForRole(tenantId, DomainEnums.Role.AUDITOR);

        mvc.perform(get("/api/integrations/tally/status").header("Authorization", "Bearer " + warehouseToken))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/integrations/tally/status").header("Authorization", "Bearer " + auditorToken))
            .andExpect(status().isOk());
    }

    @Test
    void dashboardControllerMethodsRequireDashboardViewPermission() throws Exception {
        for (var methodName : new String[]{"summary", "salesTrend", "profitLoss", "lowStock", "deadStock", "outstanding", "actions", "topProducts", "slowMovingProducts"}) {
            Method method = DashboardController.class.getDeclaredMethod(methodName);
            var annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation).as(methodName).isNotNull();
            assertThat(annotation.value()).contains("dashboard.view");
        }
    }

    @Test
    void demoDataSeederIsDisabledUnlessExplicitlyEnabled() {
        var annotation = DemoDataSeeder.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("app.demo.seed-enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
        assertThat(annotation.matchIfMissing()).isFalse();
    }

    @Test
    void livenessHealthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk());
    }

    private UUID registerTenant(String email) throws Exception {
        return UUID.fromString(registerResponse(email).get("tenantId").asText());
    }

    private JsonNode registerResponse(String email) throws Exception {
        var response = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"businessName":"Tenant","businessMode":"HYBRID","email":"%s","password":"password123","fullName":"Owner"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private String tokenForRole(UUID tenantId, DomainEnums.Role role) {
        var user = new UserAccount();
        user.email = role.name().toLowerCase() + "-" + System.nanoTime() + "@example.com";
        user.fullName = "Security Test " + role.name();
        user.passwordHash = passwordEncoder.encode("password123");
        users.save(user);

        var membership = new UserTenantMembership();
        membership.tenantId = tenantId;
        membership.userId = user.id;
        membership.role = role;
        memberships.save(membership);

        return jwtService.issue(user.id, tenantId, user.email, role);
    }
}
