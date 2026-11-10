package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAiProviderTest {
    @Test void nativeFunctionCallsPreserveSignaturesIdsAndUseOnlyFixedServerOrigin() throws Exception {
        var client=mock(HttpClient.class);var json=new ObjectMapper();
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(response("""
              {"candidates":[{"content":{"role":"model","parts":[{"thoughtSignature":"opaque-signature","functionCall":{"id":"call-1","name":"summary","args":{}}}]}}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}
              """),response("""
              {"candidates":[{"content":{"role":"model","parts":[{"text":"Recorded sales are 12 [summary]."}]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4}}
              """));
        var provider=new LlmAiProvider(config(true),json,client);
        var called=new ArrayList<AiProvider.Call>();var result=provider.complete(List.of(new AiProvider.Turn("USER","Earlier question")),"This month",List.of(tool()),call->{called.add(call);return Map.of("id","summary","data",Map.of("sales",12));});
        assertThat(result.answer()).contains("12");assertThat(result.inputTokens()).isEqualTo(16);assertThat(result.outputTokens()).isEqualTo(7);assertThat(called).hasSize(1);
        var requests=ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).sendAsync(requests.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        for(var request:requests.getAllValues()) {assertThat(request.uri().toString()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent");assertThat(request.headers().firstValue("x-goog-api-key")).contains("server-test-key");assertThat(request.timeout()).isPresent();}
        var second=body(requests.getAllValues().get(1));assertThat(second).contains("opaque-signature","call-1","functionResponse","sales");assertThat(second).doesNotContain("server-test-key");
    }
    @Test void disabledProviderNeverCallsNetworkAndUnknownToolsAreNotExecuted() throws Exception {
        var client=mock(HttpClient.class);var json=new ObjectMapper();var disabled=new LlmAiProvider(config(false),json,client);
        assertThatThrownBy(()->disabled.complete(List.of(),"hello",List.of(tool()),call->Map.of())).isInstanceOf(IllegalStateException.class);verifyNoInteractions(client);
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"execute_sql\",\"args\":{}}}]}}]}"));
        assertThatThrownBy(()->new LlmAiProvider(config(true),json,client).complete(List.of(),"hello",List.of(tool()),call->{throw new AssertionError("must never run");})).isInstanceOf(IllegalStateException.class).hasMessage("Unsupported AI tool");
    }
    @Test void repeatedFunctionCallsStopAtBudget() throws Exception {
        var client=mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"summary\",\"args\":{}}}]}}]}"));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->new LlmAiProvider(config(true),new ObjectMapper(),client).complete(List.of(),"summary",List.of(tool()),c->{calls.incrementAndGet();return Map.of();})).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(4);verify(client,times(5)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }
    private AiConfiguration config(boolean enabled){return new AiConfiguration(enabled,"gemini","gemini-test","server-test-key",5,10,60);}
    private AiProvider.Tool tool(){return new AiProvider.Tool("summary","Read permitted summary",Map.of("type","OBJECT","properties",Map.of()));}
    private java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> response(String body){
        HttpResponse<byte[]> response=new HttpResponse<>() {
            public int statusCode(){return 200;} public HttpRequest request(){return null;}
            public Optional<HttpResponse<byte[]>> previousResponse(){return Optional.empty();}
            public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
            public byte[] body(){return body.getBytes(StandardCharsets.UTF_8);}
            public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
            public java.net.URI uri(){return java.net.URI.create("https://generativelanguage.googleapis.com");}
            public HttpClient.Version version(){return HttpClient.Version.HTTP_2;}
        };
        return java.util.concurrent.CompletableFuture.completedFuture(response);
    }
    private String body(HttpRequest request){var bytes=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
        public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){var v=new byte[b.remaining()];b.get(v);bytes.writeBytes(v);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){} });return bytes.toString(StandardCharsets.UTF_8);}
}
