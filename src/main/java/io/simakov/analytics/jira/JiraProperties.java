package io.simakov.analytics.jira;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jira")
public record JiraProperties(
    String baseUrl,
    String username,
    String apiToken,
    String projectKey,
    int readTimeoutSeconds,
    int maxResults
) {

}
