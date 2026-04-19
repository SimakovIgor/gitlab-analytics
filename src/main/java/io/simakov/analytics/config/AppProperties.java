package io.simakov.analytics.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Gitlab gitlab,
    Snapshot snapshot,
    Sync sync
) {

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

    public record Sync(
        String cron,
        @Min(1) int windowDays
    ) {

    }
}
