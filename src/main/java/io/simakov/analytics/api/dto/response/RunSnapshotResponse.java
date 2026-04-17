package io.simakov.analytics.api.dto.response;

import java.time.LocalDate;

public record RunSnapshotResponse(
    int snapshotsCreated,
    LocalDate snapshotDate
) {

}
