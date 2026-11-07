package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

@Service
public class ClerkBackendClient {
    private final ClerkConfiguration config;
    private final ClerkHttpClient http;
    private final ObjectMapper json;
    public ClerkBackendClient(ClerkConfiguration config, ClerkHttpClient http, ObjectMapper json) { this.config = config; this.http = http; this.json = json; }
    public VerifiedProfile verifiedProfile(String subject) {
        requireId(subject, "user_");
        var user = http.send(request("/users/" + subject).GET().build());
        if (!subject.equals(user.path("id").asText()) || user.path("banned").asBoolean() || user.path("locked").asBoolean()) {
            throw new IllegalArgumentException("Clerk account is unavailable");
        }
        var primaryId = user.path("primary_email_address_id").asText();
        if (primaryId.isBlank()) throw new IllegalArgumentException("A verified primary email address is required");
        for (var email : user.path("email_addresses")) {
            if (primaryId.equals(email.path("id").asText()) && "verified".equals(email.path("verification").path("status").asText())) {
                var address = email.path("email_address").asText().trim().toLowerCase(Locale.ROOT);
                if (address.length() > 190 || !address.contains("@")) throw new IllegalArgumentException("Invalid verified email address");
                var name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).trim();
                return new VerifiedProfile(subject, address, name.isBlank() ? address : name.substring(0, Math.min(name.length(), 160)));
            }
        }
        throw new IllegalArgumentException("Verify your primary email address in Clerk before continuing");
    }
    public InvitationDelivery inviteOrganization(String organizationId, String email, String inviterClerkId, String redirectUrl) {
        requireId(organizationId, "org_"); requireId(inviterClerkId, "user_");
        email = email.trim().toLowerCase(Locale.ROOT);
        if (hasVerifiedMember(organizationId, email)) return new InvitationDelivery("EXISTING_MEMBER", null);
        var pending = pendingInvitation(organizationId, email);
        if (pending != null) return pending;
        var body = new LinkedHashMap<String, Object>();
        body.put("email_address", email); body.put("role", "org:member"); body.put("expires_in_days", 7);
        // This is a server-authorized StockPilot invitation. Omit optional inviter_user_id so an app ADMIN
        // need not also be a Clerk org:admin; Clerk roles never determine the local business grant.
        if (redirectUrl != null && !redirectUrl.isBlank()) body.put("redirect_url", redirectUrl);
        try {
            var created = http.send(request("/organizations/" + organizationId + "/invitations").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
            var expiry = matchingPendingExpiry(created, organizationId, email);
            if (expiry == null) throw unavailable();
            return new InvitationDelivery("SENT", expiry);
        } catch (ClerkHttpClient.ClerkRequestException ex) {
            // Only Clerk's documented duplicate races are recoverable, and only after authoritative re-reading.
            if (!ex.hasOnlyCodes(400, Set.of("already_a_member_in_organization", "organization_invitation_not_unique"))) throw ex;
            if (hasVerifiedMember(organizationId, email)) return new InvitationDelivery("EXISTING_MEMBER", null);
            var concurrent = pendingInvitation(organizationId, email);
            if (concurrent != null) return concurrent;
            throw ex;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Unable to create invitation", ex); }
    }
    private boolean hasVerifiedMember(String organizationId, String email) {
        var page = http.send(request("/organizations/" + organizationId + "/memberships?email_address="
            + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&limit=10&offset=0").GET().build());
        var data = requirePage(page, 10);
        // The exact email filter normally has one result. Never infer absence from an incomplete page.
        if (page.path("total_count").asLong() > data.size()) throw unavailable();
        for (var membership : data) {
            if (!organizationId.equals(membership.path("organization").path("id").asText())) continue;
            var subject = membership.path("public_user_data").path("user_id").asText();
            var profile = verifiedProfile(subject);
            if (!email.equals(profile.email())) throw ApiErrors.conflict("Invite this Clerk member using their current verified primary email address");
            return true;
        }
        return false;
    }
    private InvitationDelivery pendingInvitation(String organizationId, String email) {
        // Clerk's invitation list has no email filter. Bound pagination and fail closed if the result is incomplete.
        for (int offset = 0; offset < 1000; offset += 100) {
            var page = http.send(request("/organizations/" + organizationId + "/invitations?status=pending&limit=100&offset=" + offset).GET().build());
            var data = requirePage(page, 100);
            for (var invitation : data) {
                var expiry = matchingPendingExpiry(invitation, organizationId, email);
                if (expiry != null) return new InvitationDelivery("EXISTING_INVITATION", expiry);
            }
            if (offset + data.size() >= page.path("total_count").asLong()) return null;
            if (data.size() < 100) throw unavailable();
        }
        throw unavailable();
    }
    private JsonNode requirePage(JsonNode page, int limit) {
        if (page == null || !page.path("data").isArray() || page.path("data").size() > limit
            || !page.path("total_count").canConvertToLong() || page.path("total_count").asLong() < page.path("data").size()) throw unavailable();
        return page.path("data");
    }
    private Instant matchingPendingExpiry(JsonNode invitation, String organizationId, String email) {
        if (invitation == null || !organizationId.equals(invitation.path("organization_id").asText())
            || !email.equalsIgnoreCase(invitation.path("email_address").asText()) || !"pending".equals(invitation.path("status").asText())
            || !invitation.path("expires_at").canConvertToLong()) return null;
        var expiry = Instant.ofEpochMilli(invitation.path("expires_at").asLong());
        return expiry.isAfter(Instant.now()) ? expiry : null;
    }
    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "The identity provider is unavailable. Please try again shortly.");
    }
    private HttpRequest.Builder request(String path) {
        if (!config.enabled) throw new IllegalStateException("Clerk authentication is disabled");
        return HttpRequest.newBuilder(URI.create("https://api.clerk.com/v1" + path)).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + config.secretKey);
    }
    private void requireId(String value, String prefix) {
        if (value == null || !value.matches(prefix + "[A-Za-z0-9]{1,120}")) throw new IllegalArgumentException("Invalid Clerk identifier");
    }
    public record VerifiedProfile(String subject, String email, String fullName) { }
    public record InvitationDelivery(String status, Instant expiresAt) { }
}
