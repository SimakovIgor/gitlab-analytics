package io.simakov.analytics.metrics.provider;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.metrics.MetricContext;
import io.simakov.analytics.metrics.MetricsMathUtils;
import io.simakov.analytics.metrics.model.UserMetrics;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes review-side metrics: comments written, MRs reviewed, approvals given, threads started.
 */
@Component
@Order(2)
class ReviewMetricProvider implements MetricProvider {

    @Override
    public void populate(MetricContext ctx, UserMetrics.UserMetricsBuilder builder) {
        Set<Long> authoredMrIds = ctx.authoredMrs().stream()
            .map(MergeRequest::getId)
            .collect(Collectors.toSet());

        List<MergeRequestNote> userNotes = ctx.notesByMrId().values().stream()
            .flatMap(Collection::stream)
            .filter(n -> ctx.gitlabIds().contains(n.getAuthorGitlabUserId()))
            .toList();
        List<MergeRequestApproval> userApprovals = ctx.approvalsByMrId().values().stream()
            .flatMap(Collection::stream)
            .filter(a -> ctx.gitlabIds().contains(a.getApprovedByGitlabUserId()))
            .toList();

        // Review notes: non-system, on MRs the user didn't author
        List<MergeRequestNote> reviewNotes = userNotes.stream()
            .filter(n -> !n.isSystem() && !authoredMrIds.contains(n.getMergeRequestId()))
            .toList();

        Set<Long> reviewedMrIds = reviewNotes.stream()
            .map(MergeRequestNote::getMergeRequestId)
            .collect(Collectors.toCollection(HashSet::new));
        userApprovals.stream()
            .map(MergeRequestApproval::getMergeRequestId)
            .filter(id -> !authoredMrIds.contains(id))
            .forEach(reviewedMrIds::add);

        int mrsReviewedCount = reviewedMrIds.size();
        int approvalsGivenCount = (int) userApprovals.stream()
            .filter(a -> !authoredMrIds.contains(a.getMergeRequestId())).count();
        double commentsPerReviewedMr = mrsReviewedCount > 0
            ? (double) reviewNotes.size() / mrsReviewedCount
            : 0.0;

        builder
            .reviewCommentsWrittenCount(reviewNotes.size())
            .mrsReviewedCount(mrsReviewedCount)
            .approvalsGivenCount(approvalsGivenCount)
            .reviewThreadsStartedCount((int) countReviewThreadsStarted(reviewNotes, ctx.notesByMrId()))
            .commentsPerReviewedMr(MetricsMathUtils.round2(commentsPerReviewedMr));
    }

    /**
     * A thread is "started by" the user when their note is the earliest in that discussionId.
     */
    private long countReviewThreadsStarted(List<MergeRequestNote> reviewNotes,
                                           Map<Long, List<MergeRequestNote>> notesByMrId) {
        Map<Long, Instant> firstNoteByDiscussion = new HashMap<>();
        notesByMrId.values().stream()
            .flatMap(Collection::stream)
            .filter(n -> n.getDiscussionId() != null && n.getCreatedAtGitlab() != null)
            .forEach(n -> firstNoteByDiscussion.merge(
                n.getDiscussionId(),
                n.getCreatedAtGitlab(),
                (a, b) -> a.isBefore(b) ? a : b));

        return reviewNotes.stream()
            .filter(n -> n.getDiscussionId() != null && n.getCreatedAtGitlab() != null)
            .filter(n -> n.getCreatedAtGitlab().equals(firstNoteByDiscussion.get(n.getDiscussionId())))
            .count();
    }
}
