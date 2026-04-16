package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabApprovalsDto(
    @JsonProperty("approved_by") List<ApproverEntry> approvedBy
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproverEntry(GitLabUserDto user) {

    }
}
