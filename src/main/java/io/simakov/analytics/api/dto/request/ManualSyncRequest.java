package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record ManualSyncRequest(
    @NotEmpty List<Long> projectIds,
    @NotNull Instant dateFrom,
    @NotNull Instant dateTo,
    boolean fetchNotes,
    boolean fetchApprovals,
    boolean fetchCommits
) {

}
