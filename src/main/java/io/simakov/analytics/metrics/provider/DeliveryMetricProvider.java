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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes delivery and volume metrics: MR counts, lines, commit counts, active days.
 */
@Component
@Order(1)
class DeliveryMetricProvider implements MetricProvider {

    @Override
    public void populate(MetricContext ctx, UserMetrics.UserMetricsBuilder builder) {
        int mrMergedCount = (int) ctx.authoredMrs().stream()
            .filter(mr -> mr.getMergedAtGitlab() != null).count();
        Set<Long> projectsTouched = ctx.authoredMrs().stream()
            .map(MergeRequest::getTrackedProjectId)
            .collect(Collectors.toSet());

        int activeDaysCount = collectActiveDays(ctx).size();
        ChangeVolume volume = computeChangeVolume(ctx);
        double mrMergedPerActiveDay = activeDaysCount > 0
            ? (double) mrMergedCount / activeDaysCount
            : 0.0;

        builder
            .mrOpenedCount(ctx.authoredMrs().size())
            .mrMergedCount(mrMergedCount)
            .activeDaysCount(activeDaysCount)
            .repositoriesTouchedCount(projectsTouched.size())
            .commitsInMrCount(ctx.userCommits().size())
            .linesAdded(volume.linesAdded())
            .linesDeleted(volume.linesDeleted())
            .linesChanged(volume.linesAdded() + volume.linesDeleted())
            .filesChanged(volume.filesChanged())
            .avgMrSizeLines(volume.avgMrSizeLines())
            .medianMrSizeLines(volume.medianMrSizeLines())
            .avgMrSizeFiles(volume.avgMrSizeFiles())
            .mrMergedPerActiveDay(MetricsMathUtils.round2(mrMergedPerActiveDay));
    }

    /**
     * Collects distinct calendar days (UTC) on which the user had any activity:
     * commits authored, review notes written, or approvals given.
     */
    private Set<String> collectActiveDays(MetricContext ctx) {
        Set<String> activeDays = new HashSet<>();
        ctx.userCommits().stream()
            .map(MergeRequestCommit::getAuthoredDate)
            .filter(Objects::nonNull)
            .map(DateTimeUtils::toDateString)
            .forEach(activeDays::add);
        ctx.notesByMrId().values().stream()
            .flatMap(List::stream)
            .filter(n -> ctx.gitlabIds().contains(n.getAuthorGitlabUserId()))
            .map(MergeRequestNote::getCreatedAtGitlab)
            .filter(Objects::nonNull)
            .map(DateTimeUtils::toDateString)
            .forEach(activeDays::add);
        ctx.approvalsByMrId().values().stream()
            .flatMap(List::stream)
            .filter(a -> ctx.gitlabIds().contains(a.getApprovedByGitlabUserId()))
            .map(MergeRequestApproval::getApprovedAtGitlab)
            .filter(Objects::nonNull)
            .map(DateTimeUtils::toDateString)
            .forEach(activeDays::add);
        return activeDays;
    }

    private ChangeVolume computeChangeVolume(MetricContext ctx) {
        int linesAdded = ctx.userCommits().stream().mapToInt(MergeRequestCommit::getAdditions).sum();
        int linesDeleted = ctx.userCommits().stream().mapToInt(MergeRequestCommit::getDeletions).sum();
        int filesChanged = ctx.userCommits().stream().mapToInt(MergeRequestCommit::getFilesChangedCount).sum();

        // MR size in lines — all commits in the MR (not just user's), MRs with no commits excluded
        List<Integer> mrSizesLines = ctx.authoredMrs().stream()
            .map(mr -> ctx.commitsByMrId().getOrDefault(mr.getId(), List.of()).stream()
                .mapToInt(c -> c.getAdditions() + c.getDeletions()).sum())
            .filter(size -> size > 0)
            .sorted()
            .toList();

        List<Integer> mrSizesFiles = ctx.authoredMrs().stream()
            .map(MergeRequest::getFilesChangedCount)
            .sorted()
            .toList();

        return new ChangeVolume(
            linesAdded,
            linesDeleted,
            filesChanged,
            MetricsMathUtils.round2(mrSizesLines.isEmpty() ? 0 : MetricsMathUtils.mean(mrSizesLines)),
            MetricsMathUtils.round2(mrSizesLines.isEmpty() ? 0 : MetricsMathUtils.median(mrSizesLines)),
            MetricsMathUtils.round2(mrSizesFiles.isEmpty() ? 0 : MetricsMathUtils.mean(mrSizesFiles))
        );
    }

    private record ChangeVolume(int linesAdded,
                                int linesDeleted,
                                int filesChanged,
                                double avgMrSizeLines,
                                double medianMrSizeLines,
                                double avgMrSizeFiles) {
    }
}
