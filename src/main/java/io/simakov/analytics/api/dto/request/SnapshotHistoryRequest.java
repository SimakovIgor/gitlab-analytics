package io.simakov.analytics.api.dto.request;

import io.simakov.analytics.domain.model.enums.TimeGroupBy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record SnapshotHistoryRequest(
    @NotEmpty List<Long> userIds,
    @NotNull LocalDate from,
    @NotNull LocalDate to,
    @NotNull TimeGroupBy groupBy
) {

}
