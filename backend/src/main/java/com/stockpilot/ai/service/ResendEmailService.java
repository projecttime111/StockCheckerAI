package com.stockpilot.ai.service;

import com.stockpilot.ai.exception.ApiErrors;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

public class ResendEmailService implements EmailService {
    private final RestClient client;
    private final String from;

    public ResendEmailService(String apiKey, String from) {
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
            throw ApiErrors.badRequest("Resend email provider is not configured");
        }
        this.from = from;
        this.client = RestClient.builder()
            .baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public void sendVerificationEmail(String email, String verificationLink) {
        send(email, "Verify your StockPilot AI account", "Verify your account: " + verificationLink);
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetLink) {
        send(email, "Reset your StockPilot AI password", "Reset your password: " + resetLink);
    }

    private void send(String to, String subject, String text) {
        client.post()
            .uri("/emails")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("from", from, "to", to, "subject", subject, "text", text))
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void sendWorkspaceInvitation(String email, String workspaceName, String invitationLink) {
        send(email, "Join your StockPilot AI workspace", "You have been invited to " + workspaceName + ". Accept your invitation: " + invitationLink);
    }
}
