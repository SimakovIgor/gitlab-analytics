package io.simakov.analytics.metrics;

import io.simakov.analytics.metrics.model.UserMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMetricsTest {

    @Test
    void isInactiveReturnsTrueWhenAllKeyMetricsAreZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L)
            .displayName("Test")
            .mrMergedCount(0)
            .commitsInMrCount(0)
            .activeDaysCount(0)
            .mrsReviewedCount(0)
            .approvalsGivenCount(0)
            .build();

        assertThat(metrics.isInactive()).isTrue();
    }

    @Test
    void isInactiveReturnsFalseWhenMrMergedCountIsNonZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(1).commitsInMrCount(0)
            .activeDaysCount(0).mrsReviewedCount(0).approvalsGivenCount(0)
            .build();

        assertThat(metrics.isInactive()).isFalse();
    }

    @Test
    void isInactiveReturnsFalseWhenCommitsIsNonZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(0).commitsInMrCount(3)
            .activeDaysCount(0).mrsReviewedCount(0).approvalsGivenCount(0)
            .build();

        assertThat(metrics.isInactive()).isFalse();
    }

    @Test
    void isInactiveReturnsFalseWhenActiveDaysIsNonZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(0).commitsInMrCount(0)
            .activeDaysCount(1).mrsReviewedCount(0).approvalsGivenCount(0)
            .build();

        assertThat(metrics.isInactive()).isFalse();
    }

    @Test
    void isInactiveReturnsFalseWhenMrsReviewedIsNonZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(0).commitsInMrCount(0)
            .activeDaysCount(0).mrsReviewedCount(2).approvalsGivenCount(0)
            .build();

        assertThat(metrics.isInactive()).isFalse();
    }

    @Test
    void isInactiveReturnsFalseWhenApprovalsIsNonZero() {
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(0).commitsInMrCount(0)
            .activeDaysCount(0).mrsReviewedCount(0).approvalsGivenCount(5)
            .build();

        assertThat(metrics.isInactive()).isFalse();
    }

    @Test
    void isInactiveIgnoresNonKeyMetrics() {
        // reviewCommentsWrittenCount is not a key metric for isInactive
        UserMetrics metrics = UserMetrics.builder()
            .trackedUserId(1L).displayName("Test")
            .mrMergedCount(0).commitsInMrCount(0)
            .activeDaysCount(0).mrsReviewedCount(0).approvalsGivenCount(0)
            .reviewCommentsWrittenCount(10)
            .build();

        assertThat(metrics.isInactive()).isTrue();
    }
}
