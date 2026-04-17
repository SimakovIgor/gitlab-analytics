package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.request.SnapshotHistoryRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse;
import io.simakov.analytics.snapshot.SnapshotHistoryService;
import io.simakov.analytics.snapshot.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/snapshots")
@RequiredArgsConstructor
@Tag(name = "Snapshots",
     description = "Metric snapshot management and historical trend queries")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final SnapshotHistoryService snapshotHistoryService;

    @PostMapping("/run")
    @Operation(summary = "Run metric snapshot",
               description = "Calculates metrics for all enabled users and saves a snapshot. "
                   + "All request fields are optional — defaults: windowDays=30, "
                   + "reportMode=MERGED_IN_PERIOD, snapshotDate=today.")
    public RunSnapshotResponse run(@RequestBody(required = false) RunSnapshotRequest request) {
        return snapshotService.runSnapshot(request);
    }

    @PostMapping("/history")
    @Operation(summary = "Get snapshot history",
               description = "Returns metric snapshots for the requested users grouped by DAY, WEEK, or MONTH. "
                   + "Within each group the latest snapshot is used.")
    public SnapshotHistoryResponse history(@RequestBody @Valid SnapshotHistoryRequest request) {
        return snapshotHistoryService.getHistory(
            request.userIds(),
            request.from(),
            request.to(),
            request.groupBy(),
            request.reportMode());
    }
}
