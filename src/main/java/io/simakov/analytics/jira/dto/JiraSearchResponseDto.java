package io.simakov.analytics.jira.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraSearchResponseDto(
    int startAt,
    int maxResults,
    int total,
    List<JiraIssueDto> issues
) {

}
