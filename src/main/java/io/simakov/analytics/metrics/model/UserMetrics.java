package io.simakov.analytics.metrics.model;

import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * All calculated metrics for a single user in a given period.
 * Uses explicit fields for clarity and type safety, plus a toMap() helper for serialization.
 */
@Getter
@Builder
public class UserMetrics {

    private final Long trackedUserId;
    private final String displayName;

    // --- Delivery ---
    private final int mrOpenedCount;
    private final int mrMergedCount;
    private final int activeDaysCount;
    private final int repositoriesTouchedCount;
    private final int commitsInMrCount;

    // --- Change volume ---
    private final int linesAdded;
    private final int linesDeleted;
    private final int linesChanged;
    private final int filesChanged;
    private final double avgMrSizeLines;
    private final double medianMrSizeLines;
    private final double avgMrSizeFiles;

    // --- Review contribution ---
    private final int reviewCommentsWrittenCount;
    private final int mrsReviewedCount;
    private final int approvalsGivenCount;
    private final int reviewThreadsStartedCount;

    // --- Flow ---
    private final Double avgTimeToFirstReviewMinutes;
    private final Double medianTimeToFirstReviewMinutes;
    private final Double avgTimeToMergeMinutes;
    private final Double medianTimeToMergeMinutes;
    private final int reworkMrCount;
    private final double reworkRatio;
    private final int selfMergeCount;
    private final double selfMergeRatio;

    // --- Normalized ---
    private final double mrMergedPerActiveDay;
    private final double commentsPerReviewedMr;

    public Map<String, Object> toMetricsMap() {
        Map<String, Object> map = new HashMap<>();
        // Delivery
        map.put("mr_opened_count", mrOpenedCount);
        map.put("mr_merged_count", mrMergedCount);
        map.put("active_days_count", activeDaysCount);
        map.put("repositories_touched_count", repositoriesTouchedCount);
        map.put("commits_in_mr_count", commitsInMrCount);
        // Change volume
        map.put("lines_added", linesAdded);
        map.put("lines_deleted", linesDeleted);
        map.put("lines_changed", linesChanged);
        map.put("files_changed", filesChanged);
        map.put("avg_mr_size_lines", avgMrSizeLines);
        map.put("median_mr_size_lines", medianMrSizeLines);
        map.put("avg_mr_size_files", avgMrSizeFiles);
        // Review
        map.put("review_comments_written_count", reviewCommentsWrittenCount);
        map.put("mrs_reviewed_count", mrsReviewedCount);
        map.put("approvals_given_count", approvalsGivenCount);
        map.put("review_threads_started_count", reviewThreadsStartedCount);
        // Flow
        map.put("avg_time_to_first_review_minutes", avgTimeToFirstReviewMinutes);
        map.put("median_time_to_first_review_minutes", medianTimeToFirstReviewMinutes);
        map.put("avg_time_to_merge_minutes", avgTimeToMergeMinutes);
        map.put("median_time_to_merge_minutes", medianTimeToMergeMinutes);
        map.put("rework_mr_count", reworkMrCount);
        map.put("rework_ratio", reworkRatio);
        map.put("self_merge_count", selfMergeCount);
        map.put("self_merge_ratio", selfMergeRatio);
        return map;
    }

    public Map<String, Double> toNormalizedMap() {
        Map<String, Double> map = new HashMap<>();
        map.put("mr_merged_per_active_day", mrMergedPerActiveDay);
        map.put("comments_per_reviewed_mr", commentsPerReviewedMr);
        return map;
    }
}
