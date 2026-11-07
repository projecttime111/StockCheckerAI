package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.stockpilot.ai.exception.ApiException;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClerkBackendClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClerkHttpClient http = mock(ClerkHttpClient.class);
    private final ClerkBackendClient client = new ClerkBackendClient(new ClerkConfiguration(true,
        "https://identity.clerk.accounts.dev", "https://app.example.com", "test-backend-key", ""), http, json);

    @Test void verifiedProfileUsesPrimaryVerifiedEmailAndTrustedBackendEndpoint() {
        when(http.send(any())).thenReturn(json.valueToTree(Map.of("id", "user_one", "primary_email_address_id", "email_primary",
            "first_name", "Owner", "email_addresses", List.of(
                Map.of("id", "email_other", "email_address", "other@example.com", "verification", Map.of("status", "verified")),
                Map.of("id", "email_primary", "email_address", "Owner@Example.com", "verification", Map.of("status", "verified"))))));
        var profile = client.verifiedProfile("user_one");
        assertThat(profile.email()).isEqualTo("owner@example.com");
        var request = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class); verify(http).send(request.capture());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.clerk.com/v1/users/user_one");
    }
    @Test void unverifiedOrDisabledAccountsCannotBeProvisioned() {
        when(http.send(any())).thenReturn(json.valueToTree(Map.of("id", "user_one", "primary_email_address_id", "email_primary",
            "email_addresses", List.of(Map.of("id", "email_primary", "email_address", "owner@example.com", "verification", Map.of("status", "unverified"))))));
        assertThatThrownBy(() -> client.verifiedProfile("user_one")).isInstanceOf(IllegalArgumentException.class);
        when(http.send(any())).thenReturn(json.valueToTree(Map.of("id", "user_one", "banned", true)));
        assertThatThrownBy(() -> client.verifiedProfile("user_one")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void identifiersCannotChangeTheTrustedRequestPath() {
        assertThatThrownBy(() -> client.verifiedProfile("user_one/../../organizations")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.inviteOrganization("https://attacker.example", "one@example.com", "user_one", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }

    @Test void existingMemberRequiresMatchingOrganizationAndCurrentVerifiedPrimaryEmailWithoutSendingEmail() {
        when(http.send(any())).thenReturn(page(List.of(membership("org_one", "user_one")), 1), profile("one+staff@example.com", "verified"));
        var result = client.inviteOrganization("org_one", "One+Staff@Example.com", "user_owner", null);
        assertThat(result.status()).isEqualTo("EXISTING_MEMBER");
        assertThat(result.expiresAt()).isNull();
        verify(http).send(argThat(request -> request.uri().toString().equals("https://api.clerk.com/v1/organizations/org_one/memberships?email_address=one%2Bstaff%40example.com&limit=10&offset=0")));
        verify(http, never()).send(argThat(request -> request.method().equals("POST")));
    }

    @Test void unverifiedOrDifferentPrimaryEmailCannotBeTreatedAsAnExistingInvitedMember() {
        for (var candidate : List.of(profile("one@example.com", "unverified"), profile("other@example.com", "verified"))) {
            reset(http);
            when(http.send(any())).thenReturn(page(List.of(membership("org_one", "user_one")), 1), candidate);
            assertThatThrownBy(() -> client.inviteOrganization("org_one", "one@example.com", "user_owner", null)).isInstanceOf(RuntimeException.class);
            verify(http, never()).send(argThat(request -> request.method().equals("POST")));
        }
    }

    @Test void validPendingInvitationIsReusedAcrossPagesAndKeepsItsRealExpiry() {
        var expiry = Instant.now().plusSeconds(3600).toEpochMilli();
        var other = invitation("org_one", "other@example.com", expiry);
        when(http.send(any())).thenReturn(page(List.of(), 0), page(java.util.Collections.nCopies(100, other), 101),
            page(List.of(invitation("org_one", "one@example.com", expiry)), 101));
        var result = client.inviteOrganization("org_one", "one@example.com", "user_owner", null);
        assertThat(result.status()).isEqualTo("EXISTING_INVITATION");
        assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochMilli(expiry));
        verify(http).send(argThat(request -> request.uri().getQuery().equals("status=pending&limit=100&offset=100")));
        verify(http, never()).send(argThat(request -> request.method().equals("POST")));
    }

    @Test void newInvitationUsesAppAuthorizedSystemDeliveryAndOnlyMemberRoleWithSevenDayExpiry() throws Exception {
        when(http.send(any())).thenReturn(page(List.of(), 0), page(List.of(), 0), invitation("org_one", "one@example.com", Instant.now().plusSeconds(604800).toEpochMilli()));
        assertThat(client.inviteOrganization("org_one", "one@example.com", "user_admin", "https://app.example.com/auth/complete").status()).isEqualTo("SENT");
        var captured = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(captured.capture());
        var post = captured.getAllValues().getLast();
        var body = json.readTree(body(post));
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.uri().toString()).isEqualTo("https://api.clerk.com/v1/organizations/org_one/invitations");
        assertThat(body.path("role").asText()).isEqualTo("org:member");
        assertThat(body.path("expires_in_days").asInt()).isEqualTo(7);
        assertThat(body.has("inviter_user_id")).isFalse();
    }

    @Test void documentedAlreadyMemberRaceRequiresAuthoritativeMembershipAndEmailReread() {
        when(http.send(any())).thenReturn(page(List.of(), 0), page(List.of(), 0))
            .thenThrow(new ClerkHttpClient.ClerkRequestException(400, Set.of("already_a_member_in_organization")))
            .thenReturn(page(List.of(membership("org_one", "user_one")), 1), profile("one@example.com", "verified"));
        assertThat(client.inviteOrganization("org_one", "one@example.com", "user_owner", null).status()).isEqualTo("EXISTING_MEMBER");
        verify(http, times(5)).send(any());
    }

    @Test void documentedDuplicateInvitationRaceRequiresMatchingUnexpiredPendingInvitation() {
        var expiry = Instant.now().plusSeconds(600).toEpochMilli();
        when(http.send(any())).thenReturn(page(List.of(), 0), page(List.of(), 0))
            .thenThrow(new ClerkHttpClient.ClerkRequestException(400, Set.of("organization_invitation_not_unique")))
            .thenReturn(page(List.of(), 0), page(List.of(invitation("org_one", "one@example.com", expiry)), 1));
        assertThat(client.inviteOrganization("org_one", "one@example.com", "user_owner", null).status()).isEqualTo("EXISTING_INVITATION");
        verify(http, times(5)).send(any());
    }

    @Test void arbitrary422RateLimitsAndMixedErrorsAreNeverTurnedIntoSuccess() {
        for (var failure : List.of(
            new ClerkHttpClient.ClerkRequestException(422, Set.of("exclusive_organization_membership")),
            new ClerkHttpClient.ClerkRequestException(422, Set.of("organization_invitation_not_unique")),
            new ClerkHttpClient.ClerkRequestException(429, Set.of("organization_invitation_rate_limit_exceeded")),
            new ClerkHttpClient.ClerkRequestException(400, Set.of("organization_invitation_not_unique", "unknown_error")))) {
            reset(http);
            when(http.send(any())).thenReturn(page(List.of(), 0), page(List.of(), 0)).thenThrow(failure);
            assertThatThrownBy(() -> client.inviteOrganization("org_one", "one@example.com", "user_owner", null)).isSameAs(failure);
            verify(http, times(3)).send(any());
        }
    }

    @Test void expiredOrForeignOrganizationInvitationsAndMembersCannotSatisfyDuplicateRecovery() {
        var failure = new ClerkHttpClient.ClerkRequestException(400, Set.of("organization_invitation_not_unique"));
        var candidates = page(List.of(invitation("org_one", "one@example.com", Instant.now().minusSeconds(1).toEpochMilli()),
            invitation("org_other", "one@example.com", Instant.now().plusSeconds(600).toEpochMilli())), 2);
        when(http.send(any())).thenReturn(page(List.of(membership("org_other", "user_one")), 1), candidates)
            .thenThrow(failure).thenReturn(page(List.of(), 0), candidates);
        assertThatThrownBy(() -> client.inviteOrganization("org_one", "one@example.com", "user_owner", null)).isSameAs(failure);
        verify(http, never()).send(argThat(request -> request.uri().getPath().startsWith("/v1/users/")));
    }

    @Test void incompleteOrOverLimitInvitationResultsFailClosedWithoutSendingDuplicates() {
        var page = page(java.util.Collections.nCopies(100, invitation("org_one", "other@example.com", Instant.now().plusSeconds(600).toEpochMilli())), 1001);
        when(http.send(any())).thenReturn(page(List.of(), 0)).thenReturn(page);
        assertThatThrownBy(() -> client.inviteOrganization("org_one", "one@example.com", "user_owner", null)).isInstanceOf(ApiException.class);
        verify(http, times(11)).send(any());
        verify(http, never()).send(argThat(request -> request.method().equals("POST")));
    }

    @Test void upstreamErrorDecoderRetainsOnlySafeInternalCodesAndGenericPublicMessage() {
        var failure = ClerkHttpClient.upstreamError(400, json.valueToTree(Map.of("errors", List.of(Map.of(
            "code", "organization_invitation_not_unique", "message", "Private email and secret details")))));
        assertThat(failure.hasOnlyCodes(400, Set.of("organization_invitation_not_unique"))).isTrue();
        assertThat(failure.status.value()).isEqualTo(503);
        assertThat(failure.getMessage()).doesNotContain("Private", "secret");
    }

    private JsonNode page(List<JsonNode> items, long total) { return json.valueToTree(Map.of("data", items, "total_count", total)); }
    private JsonNode membership(String organization, String user) { return json.valueToTree(Map.of("organization", Map.of("id", organization), "public_user_data", Map.of("user_id", user))); }
    private JsonNode profile(String email, String status) { return json.valueToTree(Map.of("id", "user_one", "primary_email_address_id", "email_primary", "email_addresses", List.of(Map.of("id", "email_primary", "email_address", email, "verification", Map.of("status", status))))); }
    private JsonNode invitation(String organization, String email, long expiry) { return json.valueToTree(Map.of("organization_id", organization, "email_address", email, "status", "pending", "expires_at", expiry)); }
    private String body(HttpRequest request) {
        var result = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { var bytes = new byte[buffer.remaining()]; buffer.get(bytes); result.writeBytes(bytes); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() { }
        });
        return result.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
