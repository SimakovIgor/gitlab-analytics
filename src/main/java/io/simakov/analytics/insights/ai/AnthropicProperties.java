package io.simakov.analytics.insights.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic Claude API integration.
 * Bound from {@code app.anthropic.*} in application.yml.
 * <p>
 * When {@code api-key} is blank, AI insight generation is disabled and cached
 * results are returned instead (empty list on first call).
 */
@ConfigurationProperties(prefix = "app.anthropic")
public class AnthropicProperties {

    /** Anthropic API key. Empty = feature disabled. */
    private String apiKey = "";

    /** Claude model to use. Haiku is the cheapest option (~$0.001/generation). */
    private String model = "claude-haiku-4-5-20251001";

    /** Max output tokens for the AI response. */
    private int maxTokens = 1500;

    /** Cache TTL in hours — requests within this window reuse the stored result. */
    private long cacheTtlHours = 24;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getCacheTtlHours() {
        return cacheTtlHours;
    }

    public void setCacheTtlHours(long cacheTtlHours) {
        this.cacheTtlHours = cacheTtlHours;
    }
}
