package io.simakov.analytics.insights.ai;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AnthropicClient}.
 * Uses MockWebServer to simulate Anthropic API responses without real network calls.
 */
class AnthropicClientTest {

    private MockWebServer server;
    private AnthropicClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AnthropicProperties props = new AnthropicProperties();
        props.setBaseUrl(server.url("/").toString());
        props.setApiKey("test-key");
        props.setModel("claude-haiku-4-5-20251001");
        props.setMaxTokens(100);

        client = new AnthropicClient(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ── successful response ──────────────────────────────────────────────────

    @Test
    void complete_parsesTextAndTokensFromSuccessfulResponse() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {
                  "content": [{"type": "text", "text": "Вот рекомендация."}],
                  "usage": {"input_tokens": 50, "output_tokens": 10}
                }
                """));

        AnthropicClient.AnthropicResponse response = client.complete("some prompt");

        assertThat(response.text()).isEqualTo("Вот рекомендация.");
        assertThat(response.tokensUsed()).isEqualTo(60);
    }

    @Test
    void complete_sendsCorrectRequestHeaders() throws InterruptedException {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {
                  "content": [{"type": "text", "text": "ok"}],
                  "usage": {"input_tokens": 5, "output_tokens": 2}
                }
                """));

        client.complete("test prompt");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("x-api-key")).isEqualTo("test-key");
        assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    void complete_sendsModelAndMaxTokensInBody() throws InterruptedException {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {
                  "content": [{"type": "text", "text": "ok"}],
                  "usage": {"input_tokens": 5, "output_tokens": 2}
                }
                """));

        client.complete("hello");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\"");
        assertThat(body).contains("claude-haiku-4-5-20251001");
        assertThat(body).contains("\"max_tokens\"");
        assertThat(body).contains("hello");
    }

    @Test
    void complete_handlesZeroUsageTokens() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {
                  "content": [{"type": "text", "text": "insight"}],
                  "usage": null
                }
                """));

        AnthropicClient.AnthropicResponse response = client.complete("prompt");

        assertThat(response.text()).isEqualTo("insight");
        assertThat(response.tokensUsed()).isZero();
    }

    // ── 400 — insufficient credits (the real-world error) ────────────────────

    @Test
    void complete_throwsOnInsufficientCredits() {
        server.enqueue(new MockResponse()
            .setResponseCode(400)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"type":"error","error":{"type":"invalid_request_error",
                "message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}
                """));

        assertThatThrownBy(() -> client.complete("prompt"))
            .isInstanceOf(WebClientResponseException.class)
            .satisfies(ex -> {
                WebClientResponseException wcEx = (WebClientResponseException) ex;
                assertThat(wcEx.getStatusCode().value()).isEqualTo(400);
                assertThat(wcEx.getResponseBodyAsString()).contains("credit balance");
            });
    }

    @Test
    void complete_throwsOnAnyApiError() {
        server.enqueue(new MockResponse()
            .setResponseCode(401)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"type":"error","error":{"type":"authentication_error","message":"Invalid API key."}}
                """));

        assertThatThrownBy(() -> client.complete("prompt"))
            .isInstanceOf(WebClientResponseException.class)
            .satisfies(ex -> {
                WebClientResponseException wcEx = (WebClientResponseException) ex;
                assertThat(wcEx.getStatusCode().value()).isEqualTo(401);
                assertThat(wcEx.getResponseBodyAsString()).contains("Invalid API key");
            });
    }

    // ── malformed response ───────────────────────────────────────────────────

    @Test
    void complete_throwsWhenContentIsEmpty() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"content": [], "usage": {"input_tokens": 0, "output_tokens": 0}}
                """));

        assertThatThrownBy(() -> client.complete("prompt"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Empty content");
    }

    @Test
    void complete_throwsWhenContentIsNull() {
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"usage": {"input_tokens": 0, "output_tokens": 0}}
                """));

        assertThatThrownBy(() -> client.complete("prompt"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Empty content");
    }
}
