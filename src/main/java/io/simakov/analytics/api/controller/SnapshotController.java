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
import org.springframework.web.bind.annotation.RequestParam;
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
    public RunSnapshotResponse run(@RequestBody @Valid RunSnapshotRequest request) {
        return snapshotService.runSnapshot(request);
    }

    @PostMapping("/backfill")
    @Operation(summary = "Weekly snapshot backfill",
               description = "Creates weekly snapshots for the last N days (step = 7 days). "
                   + "Intended for onboarding: call once after users are added to populate history. "
                   + "Default days=360. Already existing snapshots are overwritten with fresh data.")
    public java.util.Map<String, Integer> backfill(
            @RequestParam(defaultValue = "360") int days) {
        int saved = snapshotService.runWeeklyBackfill(days);
        return java.util.Map.of("snapshotsSaved", saved);
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
            request.groupBy());
    }
}
