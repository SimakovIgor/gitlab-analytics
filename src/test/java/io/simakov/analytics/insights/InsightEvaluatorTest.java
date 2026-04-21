package io.simakov.analytics.insights;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.insights.evaluator.DeliveryDropEvaluator;
import io.simakov.analytics.insights.evaluator.HighMergeTimeEvaluator;
import io.simakov.analytics.insights.evaluator.HighReworkRatioEvaluator;
import io.simakov.analytics.insights.evaluator.InactiveMemberEvaluator;
import io.simakov.analytics.insights.evaluator.LargeMrHabitEvaluator;
import io.simakov.analytics.insights.evaluator.LowReviewDepthEvaluator;
import io.simakov.analytics.insights.evaluator.MergeTimeSpikeEvaluator;
import io.simakov.analytics.insights.evaluator.NoCodeReviewEvaluator;
import io.simakov.analytics.insights.evaluator.ReviewLoadImbalanceEvaluator;
import io.simakov.analytics.insights.evaluator.StuckMrsEvaluator;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.model.UserMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for all InsightEvaluator implementations.
 * Uses builder data — no Spring context, no database.
 */
class InsightEvaluatorTest {

    private InsightProperties props;

    @BeforeEach
    void setUp() {
        props = new InsightProperties();
        // Use defaults: stuck=24h, maxMedianTtm=24h, spikeRatio=2.0, gini=0.45,
        // largeMrLines=500, deliveryDrop=0.70, minComments=1.5, maxRework=0.35, minMrs=5
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static UserMetrics activeUser(long id, int mrMerged, int mrsReviewed) {
        return UserMetrics.builder()
            .trackedUserId(id)
            .displayName("User " + id)
            .mrMergedCount(mrMerged)
            .mrsReviewedCount(mrsReviewed)
            .commitsInMrCount(1)
            .activeDaysCount(5)
            .approvalsGivenCount(mrsReviewed)
            .build();
    }

    private static UserMetrics userWithTtm(long id, double medianTtmMinutes) {
        return UserMetrics.builder()
            .trackedUserId(id)
            .displayName("User " + id)
            .mrMergedCount(5)
            .mrsReviewedCount(3)
            .commitsInMrCount(2)
            .activeDaysCount(10)
            .approvalsGivenCount(3)
            .medianTimeToMergeMinutes(medianTtmMinutes)
            .build();
    }

    private static InsightContext ctx(Map<Long, UserMetrics> current, Map<Long, UserMetrics> previous) {
        return new InsightContext(List.of(), current, previous, List.of(), Map.of());
    }

    private static InsightContext ctxWithOpenMrs(Map<Long, UserMetrics> current, List<MergeRequest> openMrs) {
        return new InsightContext(List.of(), current, Map.of(), openMrs, Map.of());
    }

    // ── HIGH_MERGE_TIME ────────────────────────────────────────────────────

    @Test
    void highMergeTime_firesWhenTeamMedianExceedsThreshold() {
        UserMetrics u1 = userWithTtm(1L, 36 * 60.0); // 36 hours
        UserMetrics u2 = userWithTtm(2L, 30 * 60.0); // 30 hours

        InsightContext ctx = ctx(Map.of(1L, u1, 2L, u2), Map.of());
        List<TeamInsight> results = new HighMergeTimeEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.HIGH_MERGE_TIME);
        assertThat(results.get(0).kind()).isEqualTo(InsightKind.BAD);
    }

    @Test
    void highMergeTime_doesNotFireWhenBelowThreshold() {
        UserMetrics u1 = userWithTtm(1L, 10 * 60.0); // 10 hours
        InsightContext ctx = ctx(Map.of(1L, u1), Map.of());

        assertThat(new HighMergeTimeEvaluator(props).evaluate(ctx)).isEmpty();
    }

    @Test
    void highMergeTime_doesNotFireWhenNoTtmData() {
        UserMetrics u1 = activeUser(1L, 5, 3);
        InsightContext ctx = ctx(Map.of(1L, u1), Map.of());

        assertThat(new HighMergeTimeEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── MERGE_TIME_SPIKE ───────────────────────────────────────────────────

    @Test
    void mergeTimeSpike_firesWhenRatioExceedsThreshold() {
        UserMetrics curr = userWithTtm(1L, 40 * 60.0); // 40h now
        UserMetrics prev = userWithTtm(1L, 10 * 60.0); // 10h before → ratio 4.0 > 2.0

        InsightContext ctx = ctx(Map.of(1L, curr), Map.of(1L, prev));
        List<TeamInsight> results = new MergeTimeSpikeEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.MERGE_TIME_SPIKE);
    }

    @Test
    void mergeTimeSpike_doesNotFireWhenRatioBelowThreshold() {
        UserMetrics curr = userWithTtm(1L, 15 * 60.0);
        UserMetrics prev = userWithTtm(1L, 10 * 60.0); // ratio 1.5 < 2.0

        InsightContext ctx = ctx(Map.of(1L, curr), Map.of(1L, prev));
        assertThat(new MergeTimeSpikeEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── STUCK_MRS ─────────────────────────────────────────────────────────

    @Test
    void stuckMrs_firesWhenOpenMrOlderThanThreshold() {
        MergeRequest stuck = MergeRequest.builder()
            .state(MrState.OPENED)
            .createdAtGitlab(Instant.now().minus(48, ChronoUnit.HOURS))
            .build();

        InsightContext ctx = ctxWithOpenMrs(Map.of(), List.of(stuck));
        List<TeamInsight> results = new StuckMrsEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.STUCK_MRS);
        assertThat(results.get(0).kind()).isEqualTo(InsightKind.BAD);
    }

    @Test
    void stuckMrs_doesNotFireWhenMrIsRecent() {
        MergeRequest recent = MergeRequest.builder()
            .state(MrState.OPENED)
            .createdAtGitlab(Instant.now().minus(2, ChronoUnit.HOURS))
            .build();

        InsightContext ctx = ctxWithOpenMrs(Map.of(), List.of(recent));
        assertThat(new StuckMrsEvaluator(props).evaluate(ctx)).isEmpty();
    }

    @Test
    void stuckMrs_doesNotFireWhenNoOpenMrs() {
        InsightContext ctx = ctxWithOpenMrs(Map.of(), List.of());
        assertThat(new StuckMrsEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── REVIEW_LOAD_IMBALANCE ──────────────────────────────────────────────

    @Test
    void reviewLoadImbalance_firesWhenGiniExceedsThreshold() {
        // One person does 100 reviews, others do 0 → Gini ≈ 1
        Map<Long, UserMetrics> current = Map.of(
            1L, activeUser(1L, 5, 100),
            2L, activeUser(2L, 5, 0),
            3L, activeUser(3L, 5, 0),
            4L, activeUser(4L, 5, 0)
        );
        InsightContext ctx = ctx(current, Map.of());
        List<TeamInsight> results = new ReviewLoadImbalanceEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.REVIEW_LOAD_IMBALANCE);
    }

    @Test
    void reviewLoadImbalance_doesNotFireWhenEvenlyDistributed() {
        // All do same reviews → Gini = 0
        Map<Long, UserMetrics> current = Map.of(
            1L, activeUser(1L, 5, 20),
            2L, activeUser(2L, 5, 20),
            3L, activeUser(3L, 5, 20)
        );
        InsightContext ctx = ctx(current, Map.of());
        assertThat(new ReviewLoadImbalanceEvaluator(props).evaluate(ctx)).isEmpty();
    }

    @Test
    void giniComputation_perfectEqualityIsZero() {
        double gini = ReviewLoadImbalanceEvaluator.computeGini(List.of(10, 10, 10));
        assertThat(gini).isLessThan(0.01);
    }

    @Test
    void giniComputation_totalInequalityIsNearOne() {
        double gini = ReviewLoadImbalanceEvaluator.computeGini(List.of(0, 0, 0, 100));
        assertThat(gini).isGreaterThan(0.7);
    }

    // ── LARGE_MR_HABIT ─────────────────────────────────────────────────────

    @Test
    void largeMrHabit_firesWhenAvgSizeExceedsThreshold() {
        UserMetrics u = UserMetrics.builder()
            .trackedUserId(1L).displayName("Big PR Dev")
            .mrMergedCount(5).mrsReviewedCount(2)
            .commitsInMrCount(3).activeDaysCount(10)
            .approvalsGivenCount(2)
            .avgMrSizeLines(800.0) // > 500
            .build();

        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        List<TeamInsight> results = new LargeMrHabitEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.LARGE_MR_HABIT);
    }

    @Test
    void largeMrHabit_doesNotFireWhenSizeBelowThreshold() {
        UserMetrics u = UserMetrics.builder()
            .trackedUserId(1L).displayName("Small PR Dev")
            .mrMergedCount(5).mrsReviewedCount(2)
            .commitsInMrCount(3).activeDaysCount(10)
            .approvalsGivenCount(2)
            .avgMrSizeLines(200.0) // < 500
            .build();

        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        assertThat(new LargeMrHabitEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── DELIVERY_DROP ──────────────────────────────────────────────────────

    @Test
    void deliveryDrop_firesWhenCurrentBelowThreshold() {
        // prev = 100 MRs, curr = 50 MRs → ratio 0.5 < 0.7
        Map<Long, UserMetrics> curr = Map.of(1L, activeUser(1L, 50, 10));
        Map<Long, UserMetrics> prev = Map.of(1L, activeUser(1L, 100, 10));

        List<TeamInsight> results = new DeliveryDropEvaluator(props).evaluate(ctx(curr, prev));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.DELIVERY_DROP);
    }

    @Test
    void deliveryDrop_doesNotFireWhenCurrentAboveThreshold() {
        Map<Long, UserMetrics> curr = Map.of(1L, activeUser(1L, 80, 10));
        Map<Long, UserMetrics> prev = Map.of(1L, activeUser(1L, 100, 10));

        assertThat(new DeliveryDropEvaluator(props).evaluate(ctx(curr, prev))).isEmpty();
    }

    @Test
    void deliveryDrop_doesNotFireWhenNoPreviousData() {
        Map<Long, UserMetrics> curr = Map.of(1L, activeUser(1L, 10, 2));
        assertThat(new DeliveryDropEvaluator(props).evaluate(ctx(curr, Map.of()))).isEmpty();
    }

    // ── LOW_REVIEW_DEPTH ──────────────────────────────────────────────────

    @Test
    void lowReviewDepth_firesWhenCommentsPerMrBelowThreshold() {
        UserMetrics u = UserMetrics.builder()
            .trackedUserId(1L).displayName("Light Reviewer")
            .mrMergedCount(5).mrsReviewedCount(10)
            .commitsInMrCount(2).activeDaysCount(10)
            .approvalsGivenCount(10)
            .commentsPerReviewedMr(0.5) // < 1.5
            .build();

        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        List<TeamInsight> results = new LowReviewDepthEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.LOW_REVIEW_DEPTH);
    }

    @Test
    void lowReviewDepth_doesNotFireWhenNoReviewers() {
        UserMetrics u = activeUser(1L, 5, 0);
        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        assertThat(new LowReviewDepthEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── HIGH_REWORK_RATIO ─────────────────────────────────────────────────

    @Test
    void highReworkRatio_firesWhenRatioExceedsThreshold() {
        UserMetrics u = UserMetrics.builder()
            .trackedUserId(1L).displayName("Rework User")
            .mrMergedCount(10).mrsReviewedCount(5)
            .commitsInMrCount(4).activeDaysCount(15)
            .approvalsGivenCount(5)
            .reworkRatio(0.5) // > 0.35
            .build();

        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        List<TeamInsight> results = new HighReworkRatioEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.HIGH_REWORK_RATIO);
    }

    @Test
    void highReworkRatio_doesNotFireWhenBelowThreshold() {
        UserMetrics u = UserMetrics.builder()
            .trackedUserId(1L).displayName("Good Coder")
            .mrMergedCount(10).mrsReviewedCount(5)
            .commitsInMrCount(4).activeDaysCount(15)
            .approvalsGivenCount(5)
            .reworkRatio(0.2) // < 0.35
            .build();

        InsightContext ctx = ctx(Map.of(1L, u), Map.of());
        assertThat(new HighReworkRatioEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── INACTIVE_MEMBER ───────────────────────────────────────────────────

    @Test
    void inactiveMember_firesForUsersWithNoActivity() {
        UserMetrics inactive = UserMetrics.builder()
            .trackedUserId(1L).displayName("Absent Dev")
            .mrMergedCount(0).mrsReviewedCount(0)
            .commitsInMrCount(0).activeDaysCount(0)
            .approvalsGivenCount(0)
            .build();

        InsightContext ctx = ctx(Map.of(1L, inactive), Map.of());
        List<TeamInsight> results = new InactiveMemberEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.INACTIVE_MEMBER);
        assertThat(results.get(0).affectedUserIds()).contains(1L);
    }

    @Test
    void inactiveMember_doesNotFireForActiveUsers() {
        InsightContext ctx = ctx(Map.of(1L, activeUser(1L, 5, 3)), Map.of());
        assertThat(new InactiveMemberEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── NO_CODE_REVIEW ────────────────────────────────────────────────────

    @Test
    void noCodeReview_firesForActiveUserWithZeroReviews() {
        UserMetrics nonReviewer = UserMetrics.builder()
            .trackedUserId(1L).displayName("Solo Dev")
            .mrMergedCount(10) // >= 5 threshold
            .mrsReviewedCount(0)
            .commitsInMrCount(5).activeDaysCount(15)
            .approvalsGivenCount(0)
            .build();

        InsightContext ctx = ctx(Map.of(1L, nonReviewer), Map.of());
        List<TeamInsight> results = new NoCodeReviewEvaluator(props).evaluate(ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rule()).isEqualTo(InsightRule.NO_CODE_REVIEW);
    }

    @Test
    void noCodeReview_doesNotFireForLowActivityUser() {
        // Below minMrsForNoReviewCheck threshold (5)
        UserMetrics lowActivity = UserMetrics.builder()
            .trackedUserId(1L).displayName("Junior Dev")
            .mrMergedCount(2) // < 5
            .mrsReviewedCount(0)
            .commitsInMrCount(2).activeDaysCount(5)
            .approvalsGivenCount(0)
            .build();

        InsightContext ctx = ctx(Map.of(1L, lowActivity), Map.of());
        assertThat(new NoCodeReviewEvaluator(props).evaluate(ctx)).isEmpty();
    }

    @Test
    void noCodeReview_doesNotFireWhenUserReviews() {
        InsightContext ctx = ctx(Map.of(1L, activeUser(1L, 10, 5)), Map.of());
        assertThat(new NoCodeReviewEvaluator(props).evaluate(ctx)).isEmpty();
    }

    // ── TeamInsight factory ────────────────────────────────────────────────

    @Test
    void teamInsightOf_usesRuleDefaultKindAndSeverity() {
        TeamInsight insight = TeamInsight.of(
            InsightRule.HIGH_MERGE_TIME,
            "Test title",
            "Test body",
            List.of(1L, 2L)
        );

        assertThat(insight.kind()).isEqualTo(InsightRule.HIGH_MERGE_TIME.defaultKind());
        assertThat(insight.severity()).isEqualTo(InsightRule.HIGH_MERGE_TIME.defaultSeverity());
        assertThat(insight.affectedUserIds()).containsExactly(1L, 2L);
    }
}
