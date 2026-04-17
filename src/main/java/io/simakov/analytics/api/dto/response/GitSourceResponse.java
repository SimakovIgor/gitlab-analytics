package io.simakov.analytics.api.dto.response;

import java.time.Instant;

public record GitSourceResponse(
    Long id,
    String name,
    String baseUrl,
    Instant createdAt,
    Instant updatedAt
) {
}
