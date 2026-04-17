package io.simakov.analytics.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Security security,
    Gitlab gitlab,
    Snapshot snapshot
) {

    public record Security(
        @NotBlank String apiToken
    ) {
    }

    public record Gitlab(
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int maxRetries,
        @Min(1) int perPage
    ) {
    }

    public record Snapshot(
        String cron,
        @Min(1) int windowDays
    ) {
    }
}
