package io.simakov.analytics.web;

import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.insights.InsightService;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.Metric;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.InsightSummaryDto;
import io.simakov.analytics.web.dto.MrSummaryDto;
import io.simakov.analytics.web.dto.ReportPageData;
import io.simakov.analytics.web.dto.ReportSummary;
import io.simakov.analytics.web.dto.UserWithAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("PMD.GodClass")
@Service
@RequiredArgsConstructor
public class ReportViewService {

    private static final int TOP_INSIGHTS_LIMIT = 6;

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final SyncJobRepository syncJobRepository;
    private final SyncJobService syncJobService;
    private final MetricCalculationService metricCalculationService;
    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestCommitRepository commitRepository;
    private final InsightService insightService;

    @Transactional(readOnly = true)
    public ReportPageData buildReportPage(String period,
                                          List<Long> requestedProjectIds,
                                          boolean showInactive) {
        Long workspaceId = WorkspaceContext.get();
        List<GitSource> sources = gitSourceRepository.findAllByWorkspaceId(workspaceId);
        List<TrackedProject> allProjects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);
        List<TrackedUser> allUsers = trackedUserRepository.findAllByWorkspaceId(workspaceId);

        boolean hasSources = !sources.isEmpty();
        boolean hasProjects = !allProjects.isEmpty();
        boolean hasUsers = !allUsers.isEmpty();
        boolean onboardingMode = !hasProjects || !hasUsers;

        List<Long> activeJobIds = syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED)
            .stream().map(SyncJob::getId).toList();

        boolean hasSyncCompleted = syncJobRepository.existsByWorkspaceIdAndStatus(workspaceId, SyncStatus.COMPLETED)
            || syncJobRepository.existsByWorkspaceIdAndStatus(workspaceId, SyncStatus.COMPLETED_WITH_ERRORS);

        Long lastFailedSyncJobId = activeJobIds.isEmpty() && !hasSyncCompleted
            ? syncJobRepository
              .findTopByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.FAILED)
              .map(SyncJob::getId)
              .orElse(null)
            : null;

        Long enrichmentJobId = syncJobService.findActiveEnrichmentJob(workspaceId)
            .map(SyncJob::getId)
            .orElse(null);

        List<UserWithAliases> usersWithAliases = buildUsersWithAliases(allUsers);

        List<Long> releaseJobIds = syncJobService.findActiveReleaseJobIds(workspaceId);

        if (onboardingMode) {
            return new ReportPageData(
                sources, hasSources, hasProjects, hasUsers, true, hasSyncCompleted,
                activeJobIds, lastFailedSyncJobId, enrichmentJobId, usersWithAliases, allProjects,
                List.of(), period, showInactive,
                null, null, List.of(), Map.of(), null, List.of(), releaseJobIds
            );
        }

        List<Long> selectedProjectIds = (requestedProjectIds == null || requestedProjectIds.isEmpty())
            ? allProjects.stream().map(TrackedProject::getId).toList()
            : requestedProjectIds;

        List<TrackedUser> filteredUsers = showInactive
            ? allUsers
            : allUsers.stream().filter(TrackedUser::isEnabled).toList();

        int days = parsePeriodDays(period);
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, days);
        Instant prevDateFrom = DateTimeUtils.minusDays(dateFrom, days);

        List<Long> userIds = filteredUsers.stream().map(TrackedUser::getId).toList();
        List<UserMetrics> metrics = List.of();
        Map<Long, Map<String, Number>> deltas = Map.of();
        ReportSummary summary = null;

        if (!userIds.isEmpty() && !selectedProjectIds.isEmpty()) {
            Map<Long, UserMetrics> current = metricCalculationService.calculate(
                selectedProjectIds, userIds, dateFrom, dateTo);
            Map<Long, UserMetrics> previous = metricCalculationService.calculate(
                selectedProjectIds, userIds, prevDateFrom, dateFrom);

            metrics = allUsers.stream()
                .map(u -> current.get(u.getId()))
                .filter(Objects::nonNull)
                .filter(m -> showInactive || !m.isInactive())
                .sorted(Comparator.comparingInt(UserMetrics::getMrMergedCount).reversed())
                .toList();

            deltas = buildDeltas(metrics, previous);
            summary = buildSummary(metrics, previous, allUsers.size());
        }

        Map<Long, String> userNameById = allUsers.stream()
            .collect(Collectors.toMap(TrackedUser::getId, TrackedUser::getDisplayName));
        List<InsightSummaryDto> topInsights = buildTopInsights(workspaceId, period, selectedProjectIds, userNameById);

        return new ReportPageData(
            sources, hasSources, hasProjects, hasUsers, false, hasSyncCompleted,
            activeJobIds, lastFailedSyncJobId, enrichmentJobId, usersWithAliases, allProjects,
            selectedProjectIds, period, showInactive,
            dateFrom, dateTo, metrics, deltas, summary, topInsights, releaseJobIds
        );
    }

    private List<InsightSummaryDto> buildTopInsights(Long workspaceId,
                                                     String period,
                                                     List<Long> projectIds,
                                                     Map<Long, String> userNameById) {
        return insightService.evaluate(workspaceId, period, projectIds).stream()
            .filter(i -> i.kind() == InsightKind.BAD || i.kind() == InsightKind.WARN)
            .limit(TOP_INSIGHTS_LIMIT)
            .map(i -> toInsightSummary(i, userNameById))
            .toList();
    }

    private InsightSummaryDto toInsightSummary(TeamInsight insight,
                                               Map<Long, String> userNameById) {
        List<Long> ids = insight.affectedUserIds().stream()
            .filter(userNameById::containsKey)
            .limit(3)
            .toList();
        List<String> names = ids.stream().map(userNameById::get).toList();
        return new InsightSummaryDto(
            insight.kind().name().toLowerCase(Locale.ROOT),
            insight.title(),
            insight.body(),
            names,
            ids
        );
    }

    private List<UserWithAliases> buildUsersWithAliases(List<TrackedUser> users) {
        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();
        Map<Long, List<TrackedUserAlias>> aliasesByUserId = aliasRepository
            .findByTrackedUserIdIn(userIds)
            .stream()
            .collect(Collectors.groupingBy(TrackedUserAlias::getTrackedUserId));
        return users.stream()
            .map(u -> new UserWithAliases(u, aliasesByUserId.getOrDefault(u.getId(), List.of())))
            .toList();
    }

    private Map<Long, Map<String, Number>> buildDeltas(List<UserMetrics> current,
                                                       Map<Long, UserMetrics> prev) {
        Map<Long, Map<String, Number>> result = new HashMap<>();
        for (UserMetrics m : current) {
            UserMetrics p = prev.get(m.getTrackedUserId());
            if (p != null) {
                result.put(m.getTrackedUserId(), computeDelta(m, p));
            }
        }
        return result;
    }

    private Map<String, Number> computeDelta(UserMetrics m,
                                             UserMetrics p) {
        Map<String, Number> d = new HashMap<>();
        d.put(Metric.MR_MERGED_COUNT.key(), m.getMrMergedCount() - p.getMrMergedCount());
        d.put(Metric.LINES_ADDED.key(), m.getLinesAdded() - p.getLinesAdded());
        d.put(Metric.LINES_DELETED.key(), m.getLinesDeleted() - p.getLinesDeleted());
        d.put(Metric.COMMITS_IN_MR_COUNT.key(), m.getCommitsInMrCount() - p.getCommitsInMrCount());
        d.put(Metric.REVIEW_COMMENTS_WRITTEN_COUNT.key(), m.getReviewCommentsWrittenCount() - p.getReviewCommentsWrittenCount());
        d.put(Metric.MRS_REVIEWED_COUNT.key(), m.getMrsReviewedCount() - p.getMrsReviewedCount());
        d.put(Metric.APPROVALS_GIVEN_COUNT.key(), m.getApprovalsGivenCount() - p.getApprovalsGivenCount());
        d.put(Metric.ACTIVE_DAYS_COUNT.key(), m.getActiveDaysCount() - p.getActiveDaysCount());
        if (m.getAvgTimeToMergeMinutes() != null && p.getAvgTimeToMergeMinutes() != null) {
            d.put(Metric.AVG_TIME_TO_MERGE_MINUTES.key(), m.getAvgTimeToMergeMinutes() - p.getAvgTimeToMergeMinutes());
        }
        return d;
    }

    private ReportSummary buildSummary(List<UserMetrics> metrics,
                                       Map<Long, UserMetrics> previous,
                                       int totalDevs) {
        int totalMrMerged = metrics.stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        int activeDevs = (int) metrics.stream().filter(m -> !m.isInactive()).count();
        int totalComments = metrics.stream().mapToInt(UserMetrics::getReviewCommentsWrittenCount).sum();
        Double medianTtm = computeMedianHours(metrics);

        List<UserMetrics> prevList = List.copyOf(previous.values());
        int prevMrMerged = prevList.stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        int prevComments = prevList.stream().mapToInt(UserMetrics::getReviewCommentsWrittenCount).sum();
        Double prevMedian = computeMedianHours(prevList);

        Integer deltaMr = previous.isEmpty()
            ? null
            : totalMrMerged - prevMrMerged;
        Integer deltaComments = previous.isEmpty()
            ? null
            : totalComments - prevComments;
        Double deltaMedian = (medianTtm != null && prevMedian != null)
            ? medianTtm - prevMedian
            : null;

        return new ReportSummary(
            totalMrMerged, deltaMr,
            activeDevs, totalDevs,
            medianTtm, deltaMedian,
            totalComments, deltaComments
        );
    }

    private Double computeMedianHours(List<UserMetrics> list) {
        List<Double> values = list.stream()
            .map(UserMetrics::getAvgTimeToMergeMinutes)
            .filter(Objects::nonNull)
            .map(v -> v / 60.0)
            .sorted()
            .toList();
        if (values.isEmpty()) {
            return null;
        }
        int mid = values.size() / 2;
        return values.size() % 2 == 0
            ? (values.get(mid - 1) + values.get(mid)) / 2.0
            : values.get(mid);
    }

    @Transactional(readOnly = true)
    public List<MrSummaryDto> getUserMrs(Long userId,
                                         String period,
                                         List<Long> requestedProjectIds) {
        Long workspaceId = WorkspaceContext.get();
        trackedUserRepository.findById(userId)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", userId));

        List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(userId);

        List<Long> gitlabUserIds = aliases.stream()
            .map(TrackedUserAlias::getGitlabUserId)
            .filter(Objects::nonNull)
            .toList();

        List<TrackedProject> allProjects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);
        List<Long> projectIds = (requestedProjectIds == null || requestedProjectIds.isEmpty())
            ? allProjects.stream().map(TrackedProject::getId).toList()
            : requestedProjectIds;
        if (projectIds.isEmpty()) {
            return List.of();
        }

        int days = parsePeriodDays(period);
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = dateTo.minus(days, ChronoUnit.DAYS);

        Map<Long, String> projectPathById = allProjects.stream()
            .collect(Collectors.toMap(TrackedProject::getId, TrackedProject::getPathWithNamespace));

        Set<String> userEmails = buildUserEmails(userId, aliases);

        List<MergeRequest> mrs;
        if (gitlabUserIds.isEmpty()) {
            mrs = findMrsByEmailFallbackRaw(userId, aliases, projectIds, dateFrom, dateTo);
        } else {
            mrs = mergeRequestRepository.findMergedInPeriodByAuthors(projectIds, gitlabUserIds, dateFrom, dateTo);
        }

        return toMrSummaries(mrs, projectPathById, userEmails);
    }

    private List<MergeRequest> findMrsByEmailFallbackRaw(Long userId,
                                                         List<TrackedUserAlias> aliases,
                                                         List<Long> projectIds,
                                                         Instant dateFrom,
                                                         Instant dateTo) {
        TrackedUser user = trackedUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }
        Set<String> emails = buildUserEmails(userId, aliases);
        if (emails.isEmpty()) {
            return List.of();
        }
        return mergeRequestRepository
            .findMergedInPeriodByCommitEmails(projectIds, List.copyOf(emails), dateFrom, dateTo);
    }

    private Set<String> buildUserEmails(Long userId,
                                        List<TrackedUserAlias> aliases) {
        Set<String> emails = new HashSet<>();
        aliases.forEach(a -> {
            if (a.getEmail() != null) {
                emails.add(a.getEmail().toLowerCase(Locale.ROOT));
            }
        });
        trackedUserRepository.findById(userId).ifPresent(u -> {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                emails.add(u.getEmail().toLowerCase(Locale.ROOT));
            }
        });
        return emails;
    }

    List<MrSummaryDto> toMrSummaries(List<MergeRequest> mrs,
                                     Map<Long, String> projectPathById,
                                     Set<String> userEmails) {
        if (mrs.isEmpty()) {
            return List.of();
        }
        List<Long> mrIds = mrs.stream().map(MergeRequest::getId).toList();
        Map<Long, MrCommitStats> statsByMrId = buildCommitStats(mrIds, userEmails);
        return mrs.stream()
            .map(mr -> toMrSummary(mr, projectPathById, statsByMrId.getOrDefault(mr.getId(), MrCommitStats.EMPTY)))
            .toList();
    }

    private Map<Long, MrCommitStats> buildCommitStats(List<Long> mrIds,
                                                      Set<String> userEmails) {
        List<MergeRequestCommit> commits = commitRepository.findByMergeRequestIdIn(mrIds);
        Map<Long, MrCommitStats> result = new HashMap<>();
        for (MergeRequestCommit c : commits) {
            String email = c.getAuthorEmail() != null
                ? c.getAuthorEmail().toLowerCase(Locale.ROOT)
                : "";
            if (!userEmails.isEmpty() && !userEmails.contains(email)) {
                continue;
            }
            // Merge commits (parent_ids > 1) carry inflated stats from the merged branch —
            // they must not contribute to line counts (commit count still includes them).
            int additions = c.isMergeCommit()
                ? 0
                : c.getAdditions();
            int deletions = c.isMergeCommit()
                ? 0
                : c.getDeletions();
            result.merge(c.getMergeRequestId(),
                new MrCommitStats(additions, deletions, 1),
                MrCommitStats::merge);
        }
        return result;
    }

    private MrSummaryDto toMrSummary(MergeRequest mr,
                                     Map<Long, String> projectPathById,
                                     MrCommitStats stats) {
        String projectPath = projectPathById.getOrDefault(mr.getTrackedProjectId(), "");
        Double hoursToMerge = null;
        if (mr.getMergedAtGitlab() != null && mr.getCreatedAtGitlab() != null) {
            long seconds = mr.getMergedAtGitlab().getEpochSecond() - mr.getCreatedAtGitlab().getEpochSecond();
            hoursToMerge = Math.round(seconds / 3600.0 * 10.0) / 10.0;
        }
        String createdAt = mr.getCreatedAtGitlab() != null
            ? mr.getCreatedAtGitlab().toString()
            : null;
        String mergedAt = mr.getMergedAtGitlab() != null
            ? mr.getMergedAtGitlab().toString()
            : null;
        // Prefer net diff from /diffs endpoint (matches GitLab UI); fall back to commit stats.
        int linesAdded = mr.getNetAdditions() != null
            ? mr.getNetAdditions()
            : stats.linesAdded();
        int linesDeleted = mr.getNetDeletions() != null
            ? mr.getNetDeletions()
            : stats.linesDeleted();
        return new MrSummaryDto(mr.getTitle(), projectPath, mr.getWebUrl(),
            createdAt, mergedAt, hoursToMerge,
            linesAdded, linesDeleted, stats.commitCount());
    }

    private int parsePeriodDays(String period) {
        try {
            return PeriodType.valueOf(period).toDays();
        } catch (IllegalArgumentException e) {
            return PeriodType.LAST_30_DAYS.toDays();
        }
    }

    record MrCommitStats(int linesAdded, int linesDeleted, int commitCount) {

        static final MrCommitStats EMPTY = new MrCommitStats(0, 0, 0);

        static MrCommitStats merge(MrCommitStats a,
                                   MrCommitStats b) {
            return new MrCommitStats(a.linesAdded + b.linesAdded,
                a.linesDeleted + b.linesDeleted,
                a.commitCount + b.commitCount);
        }
    }
}
