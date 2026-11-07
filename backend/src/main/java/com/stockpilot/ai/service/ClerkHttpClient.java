package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;
import com.stockpilot.ai.exception.ApiException;
import org.springframework.http.HttpStatus;

/** No redirects or token-controlled destinations; every caller constructs a trusted URL. */
@Component
public class ClerkHttpClient {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public ClerkHttpClient(ObjectMapper mapper) { this.mapper = mapper; }
    public JsonNode send(HttpRequest request) {
        var pending = client.sendAsync(request, info -> new LimitedBodySubscriber());
        try {
            var response = pending.get(6, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamError(response.statusCode(), mapper.readTree(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); pending.cancel(true); throw unavailable();
        } catch (ExecutionException | TimeoutException | java.io.IOException ex) { pending.cancel(true); throw unavailable(); }
    }
    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "The identity provider is unavailable. Please try again shortly.");
    }
    /** Keep only machine-readable codes internally; upstream bodies, headers and messages never reach clients. */
    static ClerkRequestException upstreamError(int status, JsonNode body) {
        var codes = new HashSet<String>();
        if (body != null) for (var error : body.path("errors")) {
            var code = error.path("code").asText();
            if (code.matches("[a-z_]{1,100}")) codes.add(code);
            else codes.add("unknown_upstream_error");
        }
        return new ClerkRequestException(status, codes);
    }
    static final class ClerkRequestException extends ApiException {
        private final int upstreamStatus;
        private final Set<String> codes;
        ClerkRequestException(int upstreamStatus, Set<String> codes) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "The identity provider is unavailable. Please try again shortly.");
            this.upstreamStatus = upstreamStatus; this.codes = Set.copyOf(codes);
        }
        boolean hasOnlyCodes(int status, Set<String> acceptedCodes) {
            return upstreamStatus == status && !codes.isEmpty() && acceptedCodes.containsAll(codes);
        }
    }
    /** Limit bytes while receiving, rather than allocating an unbounded response before checking its size. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 262144) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Clerk response too large")); return;
                }
                var data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
