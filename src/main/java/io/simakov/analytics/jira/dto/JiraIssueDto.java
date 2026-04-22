package io.simakov.analytics.jira.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraIssueDto(
    String key,
    Fields fields
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fields(
        String summary,
        OffsetDateTime created,
        OffsetDateTime resolutiondate,
        List<Component> components,
        OffsetDateTime impactStartedAt,
        OffsetDateTime impactEndedAt
    ) {

        /**
         * Constructor for backward-compatible deserialization — impact fields default to null.
         */
        public Fields(String summary,
                      OffsetDateTime created,
                      OffsetDateTime resolutiondate,
                      List<Component> components) {
            this(summary, created, resolutiondate, components, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(
        String name
    ) {

    }
}
