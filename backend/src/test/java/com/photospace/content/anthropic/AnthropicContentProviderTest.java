package com.photospace.content.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.jayway.jsonpath.JsonPath;
import com.photospace.content.ContentGenerationException;
import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.content.ContentProperties;
import com.photospace.content.GenerationRequest;
import com.photospace.content.GenerationResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The adapter against a local stub of the Messages API: the request it sends, how it reads the answer, and how
 * each kind of failure maps to a provider-neutral kind without leaking the provider's message.
 */
class AnthropicContentProviderTest {

    private static final byte[] JPEG_1 = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
    private static final byte[] JPEG_2 = {(byte) 0xFF, (byte) 0xD8, 4, 5, 6};

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> requestHeaders = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";
    private volatile Duration responseDelay = Duration.ZERO;
    private AnthropicClient client;
    private AnthropicContentProvider provider;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
            try {
                Thread.sleep(responseDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ContentProperties.Anthropic properties = new ContentProperties.Anthropic(
                "test-key", "claude-opus-5-5", "medium", Duration.ofMillis(500), 16000, 0, null);
        client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .timeout(properties.timeout())
                .maxRetries(0)
                .build();
        provider = new AnthropicContentProvider(client, properties);
    }

    @AfterEach
    void stopStub() {
        client.close();
        server.stop(0);
    }

    @Test
    void sendsThePhotosAndDataAndReadsTheStructuredAnswer() {
        respond(200, message("end_turn", """
                {"captions":[{"caption":"Golden hour in the park.","call_to_action":"Book your session.","hashtags":["family","#goldenhour"]}],
                 "carousel_order":["photo-2","photo-1"],"carousel_rationale":"The wide shot first.",
                 "story_ideas":["Behind the scenes"],"warnings":["No location given."]}"""));

        GenerationResult result = provider.generate(request());

        assertThat(result.captions()).containsExactly(new GenerationResult.Caption(
                "Golden hour in the park.", "Book your session.", List.of("family", "#goldenhour")));
        assertThat(result.carouselOrder()).containsExactly("photo-2", "photo-1");
        assertThat(result.carouselRationale()).isEqualTo("The wide shot first.");
        assertThat(result.storyIdeas()).containsExactly("Behind the scenes");
        assertThat(result.warnings()).containsExactly("No location given.");
        assertThat(result.inputTokens()).isEqualTo(1234);
        assertThat(result.outputTokens()).isEqualTo(321);

        String body = requestBody.get();
        assertThat(JsonPath.<String>read(body, "$.model")).isEqualTo("claude-opus-5-5");
        assertThat(JsonPath.<Integer>read(body, "$.max_tokens")).isEqualTo(16000);
        assertThat(JsonPath.<String>read(body, "$.output_config.effort")).isEqualTo("medium");
        assertThat(JsonPath.<String>read(body, "$.output_config.format.type")).isEqualTo("json_schema");
        assertThat(JsonPath.<List<String>>read(body, "$.output_config.format.schema.required"))
                .contains("captions", "carousel_order", "warnings");
        assertThat(JsonPath.<String>read(body, "$.fallbacks")).isEqualTo("default");
        assertThat(body).contains("Ground rules");
        assertThat(JsonPath.<List<String>>read(body, "$.messages[0].content[*].type"))
                .containsExactly("text", "image", "text", "image", "text");
        assertThat(JsonPath.<String>read(body, "$.messages[0].content[0].text")).isEqualTo("photo-1:");
        assertThat(JsonPath.<String>read(body, "$.messages[0].content[1].source.media_type")).isEqualTo("image/jpeg");
        assertThat(JsonPath.<String>read(body, "$.messages[0].content[1].source.data"))
                .isEqualTo(Base64.getEncoder().encodeToString(JPEG_1));
        assertThat(JsonPath.<String>read(body, "$.messages[0].content[3].source.data"))
                .isEqualTo(Base64.getEncoder().encodeToString(JPEG_2));
        assertThat(JsonPath.<String>read(body, "$.messages[0].content[4].text"))
                .contains("<notes>Picnic &amp; kites</notes>", "<type>Family</type>", "Write 2 caption options");
        assertThat(requestHeaders.get().get("X-api-key")).containsExactly("test-key");
        assertThat(String.join(",", requestHeaders.get().get("Anthropic-beta")))
                .contains(AnthropicContentProvider.FALLBACK_BETA);
    }

    @ParameterizedTest
    @CsvSource({
        "429, rate_limit_error, RATE_LIMITED",
        "401, authentication_error, NOT_CONFIGURED",
        "403, permission_error, NOT_CONFIGURED",
        "404, not_found_error, NOT_CONFIGURED",
        "400, invalid_request_error, REJECTED",
        "413, request_too_large, REJECTED",
        "500, api_error, UNAVAILABLE",
        "529, overloaded_error, UNAVAILABLE"
    })
    void mapsErrorResponsesWithoutTheProvidersMessage(int code, String type, Kind expected) {
        respond(code, """
                {"type":"error","error":{"type":"%s","message":"provider detail that must not be shown"}}"""
                .formatted(type));

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOfSatisfying(ContentGenerationException.class, e -> {
                    assertThat(e.kind()).isEqualTo(expected);
                    assertThat(e.getMessage()).isEqualTo(expected.userMessage()).doesNotContain("provider detail");
                });
    }

    @Test
    void aRefusalIsPermanent() {
        respond(200, message("refusal", ""));

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOfSatisfying(ContentGenerationException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.REFUSED);
                    assertThat(e.kind().retryable()).isFalse();
                });
    }

    @Test
    void aTruncatedOrMalformedAnswerIsRetryable() {
        respond(200, message("max_tokens", "{\"captions\":["));
        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOfSatisfying(ContentGenerationException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID_OUTPUT));

        respond(200, message("end_turn", "Here are your captions!"));
        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOfSatisfying(ContentGenerationException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.INVALID_OUTPUT);
                    assertThat(e.kind().retryable()).isTrue();
                });
    }

    @Test
    void aSlowAnswerTimesOutAsUnavailable() {
        respond(200, message("end_turn", "{}"));
        responseDelay = Duration.ofSeconds(2);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOfSatisfying(ContentGenerationException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    private void respond(int code, String body) {
        status = code;
        responseBody = body;
    }

    private static String message(String stopReason, String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"%s"}],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1234,"output_tokens":321}}""".formatted(escaped, stopReason);
    }

    private static GenerationRequest request() {
        return new GenerationRequest(
                new GenerationRequest.Brand("Warm", "Families", null, "clichés"),
                "Family",
                "Picnic & kites",
                List.of(new GenerationRequest.Photo("photo-1", JPEG_1), new GenerationRequest.Photo("photo-2", JPEG_2)),
                2,
                true,
                true);
    }
}
