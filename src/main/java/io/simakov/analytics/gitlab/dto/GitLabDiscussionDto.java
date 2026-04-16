package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabDiscussionDto(
    String id,
    @JsonProperty("individual_note") boolean individualNote,
    List<GitLabNoteDto> notes
) {

}
