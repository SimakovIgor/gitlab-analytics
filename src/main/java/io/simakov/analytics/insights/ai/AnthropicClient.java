package io.simakov.analytics.insights.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin WebClient wrapper for the Anthropic Messages API.
 * Performs a synchronous (blocking) call — AI insight generation is a low-frequency,
 * user-triggered action, so blocking is acceptable here.
 */
@Slf4j
@Component
public class AnthropicClient {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final AnthropicProperties props;

    public AnthropicClient(WebClient.Builder builder, AnthropicProperties props) {
        this.props = props;
        this.webClient = builder
            .baseUrl(ANTHROPIC_API_URL)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * Sends a prompt to Claude and returns the raw text content of the first response block.
     *
     * @param prompt the user-role message text
     * @return Claude's response text
     * @throws RuntimeException if the API call fails or times out
     */
    @SuppressWarnings("unchecked")
    public AnthropicResponse complete(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "model", props.getModel(),
            "max_tokens", props.getMaxTokens(),
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            )
        );

        Map<String, Object> response = webClient.post()
            .header("x-api-key", props.getApiKey())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(TIMEOUT)
            .block();

        if (response == null) {
            throw new IllegalStateException("Null response from Anthropic API");
        }

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) {
            throw new IllegalStateException("Empty content in Anthropic response");
        }

        String text = (String) content.get(0).get("text");

        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        int inputTokens = usage != null ? ((Number) usage.getOrDefault("input_tokens", 0)).intValue() : 0;
        int outputTokens = usage != null ? ((Number) usage.getOrDefault("output_tokens", 0)).intValue() : 0;
        int totalTokens = inputTokens + outputTokens;

        log.info("Anthropic API call: {} input + {} output = {} tokens",
            inputTokens, outputTokens, totalTokens);

        return new AnthropicResponse(text, totalTokens);
    }

    /**
     * Carries the text response and total token count from a single API call.
     */
    public record AnthropicResponse(String text, int tokensUsed) {

    }
}
