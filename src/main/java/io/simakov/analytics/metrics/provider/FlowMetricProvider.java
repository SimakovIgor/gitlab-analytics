package io.simakov.analytics.metrics.provider;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.metrics.MetricContext;
import io.simakov.analytics.metrics.MetricsMathUtils;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes flow metrics: time-to-first-review, time-to-merge, and rework detection.
 */
@Component
@Order(3)
class FlowMetricProvider implements MetricProvider {

    @Override
    public void populate(MetricContext ctx, UserMetrics.UserMetricsBuilder builder) {
        // Group user's own commits by MR for rework detection
        Map<Long, List<MergeRequestCommit>> userCommitsByMrId = ctx.userCommits().stream()
            .collect(Collectors.groupingBy(MergeRequestCommit::getMergeRequestId));

        List<Long> timeToFirstReview = new ArrayList<>();
        List<Long> timeToMerge = new ArrayList<>();
        int reworkMrCount = 0;

        for (MergeRequest mr : ctx.authoredMrs()) {
            List<MergeRequestNote> mrNotes = ctx.notesByMrId().getOrDefault(mr.getId(), List.of());
            List<MergeRequestApproval> mrApprovals = ctx.approvalsByMrId().getOrDefault(mr.getId(), List.of());

            Optional<Instant> firstExternalReview = findFirstExternalReviewEvent(
                ctx.gitlabIds(), mrNotes, mrApprovals);

            if (firstExternalReview.isPresent()) {
                long minutes = DateTimeUtils.minutesBetween(mr.getCreatedAtGitlab(), firstExternalReview.get());
                if (minutes >= 0) {
                    timeToFirstReview.add(minutes);
                }
                if (isReworked(mr.getId(), userCommitsByMrId, firstExternalReview.get())) {
                    reworkMrCount++;
                }
            }

            collectTimeToMerge(mr, timeToMerge);
        }

        int mrMergedCount = (int) ctx.authoredMrs().stream()
            .filter(mr -> mr.getMergedAtGitlab() != null).count();
        double reworkRatio = mrMergedCount > 0 ? (double) reworkMrCount / mrMergedCount : 0.0;

        builder
            .avgTimeToFirstReviewMinutes(MetricsMathUtils.optMean(timeToFirstReview))
            .medianTimeToFirstReviewMinutes(MetricsMathUtils.optMedian(timeToFirstReview))
            .avgTimeToMergeMinutes(MetricsMathUtils.optMean(timeToMerge))
            .medianTimeToMergeMinutes(MetricsMathUtils.optMedian(timeToMerge))
            .reworkMrCount(reworkMrCount)
            .reworkRatio(MetricsMathUtils.round2(reworkRatio));
    }

    /**
     * Earliest external review event: non-system note OR approval from someone other than the MR author.
     */
    private Optional<Instant> findFirstExternalReviewEvent(Set<Long> authorGitlabIds,
                                                           List<MergeRequestNote> notes,
                                                           List<MergeRequestApproval> approvals) {
        Optional<Instant> firstNote = notes.stream()
            .filter(n -> !n.isSystem())
            .filter(n -> n.getAuthorGitlabUserId() != null
                && !authorGitlabIds.contains(n.getAuthorGitlabUserId()))
            .map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        Optional<Instant> firstApproval = approvals.stream()
            .filter(a -> a.getApprovedByGitlabUserId() != null
                && !authorGitlabIds.contains(a.getApprovedByGitlabUserId()))
            .map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull)
            .min(Instant::compareTo);

        if (firstNote.isEmpty()) {
            return firstApproval;
        }
        return firstApproval
            .map(instant -> firstNote.get().isBefore(instant) ? firstNote.get() : instant)
            .or(() -> firstNote);
    }

    /**
     * Rework: author pushed a commit after the first external review event.
     */
    private boolean isReworked(Long mrId,
                               Map<Long, List<MergeRequestCommit>> userCommitsByMrId,
                               Instant firstExternalReview) {
        return userCommitsByMrId.getOrDefault(mrId, List.of()).stream()
            .filter(c -> c.getAuthoredDate() != null)
            .anyMatch(c -> c.getAuthoredDate().isAfter(firstExternalReview));
    }

    private void collectTimeToMerge(MergeRequest mr, List<Long> timeToMerge) {
        if (mr.getMergedAtGitlab() == null) {
            return;
        }
        long minutes = DateTimeUtils.minutesBetween(mr.getCreatedAtGitlab(), mr.getMergedAtGitlab());
        if (minutes >= 0) {
            timeToMerge.add(minutes);
        }
    }
}
