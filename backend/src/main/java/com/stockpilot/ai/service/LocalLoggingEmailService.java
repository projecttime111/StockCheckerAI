package com.stockpilot.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalLoggingEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(LocalLoggingEmailService.class);

    @Override
    public void sendVerificationEmail(String email, String verificationLink) {
        log.warn("LOCAL EMAIL ONLY - verification email for {}: {}", email, verificationLink);
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetLink) {
        log.warn("LOCAL EMAIL ONLY - password reset email for {}: {}", email, resetLink);
    }

    @Override
    public void sendWorkspaceInvitation(String email, String workspaceName, String invitationLink) {
        log.warn("LOCAL EMAIL ONLY - workspace invitation for {} to {}: {}", email, workspaceName, invitationLink);
    }
}
