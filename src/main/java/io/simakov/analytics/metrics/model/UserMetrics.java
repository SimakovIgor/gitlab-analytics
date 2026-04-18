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
    // --- Normalized ---
    private final double mrMergedPerActiveDay;
    private final double commentsPerReviewedMr;

    /**
     * Сотрудник считается неактивным, если все ключевые метрики за период равны нулю.
     */
    public boolean isInactive() {
        return mrMergedCount == 0
            && commitsInMrCount == 0
            && activeDaysCount == 0
            && mrsReviewedCount == 0
            && approvalsGivenCount == 0;
    }

    public Map<String, Object> toMetricsMap() {
        Map<String, Object> map = new HashMap<>();
        // Delivery
        map.put(Metric.MR_OPENED_COUNT.key(), mrOpenedCount);
        map.put(Metric.MR_MERGED_COUNT.key(), mrMergedCount);
        map.put(Metric.ACTIVE_DAYS_COUNT.key(), activeDaysCount);
        map.put(Metric.REPOSITORIES_TOUCHED_COUNT.key(), repositoriesTouchedCount);
        map.put(Metric.COMMITS_IN_MR_COUNT.key(), commitsInMrCount);
        // Change volume
        map.put(Metric.LINES_ADDED.key(), linesAdded);
        map.put(Metric.LINES_DELETED.key(), linesDeleted);
        map.put(Metric.LINES_CHANGED.key(), linesChanged);
        map.put(Metric.FILES_CHANGED.key(), filesChanged);
        map.put(Metric.AVG_MR_SIZE_LINES.key(), avgMrSizeLines);
        map.put(Metric.MEDIAN_MR_SIZE_LINES.key(), medianMrSizeLines);
        map.put(Metric.AVG_MR_SIZE_FILES.key(), avgMrSizeFiles);
        // Review
        map.put(Metric.REVIEW_COMMENTS_WRITTEN_COUNT.key(), reviewCommentsWrittenCount);
        map.put(Metric.MRS_REVIEWED_COUNT.key(), mrsReviewedCount);
        map.put(Metric.APPROVALS_GIVEN_COUNT.key(), approvalsGivenCount);
        map.put(Metric.REVIEW_THREADS_STARTED_COUNT.key(), reviewThreadsStartedCount);
        // Flow
        map.put(Metric.AVG_TIME_TO_FIRST_REVIEW_MINUTES.key(), avgTimeToFirstReviewMinutes);
        map.put(Metric.MEDIAN_TIME_TO_FIRST_REVIEW_MINUTES.key(), medianTimeToFirstReviewMinutes);
        map.put(Metric.AVG_TIME_TO_MERGE_MINUTES.key(), avgTimeToMergeMinutes);
        map.put(Metric.MEDIAN_TIME_TO_MERGE_MINUTES.key(), medianTimeToMergeMinutes);
        map.put(Metric.REWORK_MR_COUNT.key(), reworkMrCount);
        map.put(Metric.REWORK_RATIO.key(), reworkRatio);
        return map;
    }

    public Map<String, Double> toNormalizedMap() {
        Map<String, Double> map = new HashMap<>();
        map.put(Metric.MR_MERGED_PER_ACTIVE_DAY.key(), mrMergedPerActiveDay);
        map.put(Metric.COMMENTS_PER_REVIEWED_MR.key(), commentsPerReviewedMr);
        return map;
    }
}
