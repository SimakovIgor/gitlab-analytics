package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.ReportPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ReportViewService {

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final SyncJobRepository syncJobRepository;
    private final MetricCalculationService metricCalculationService;

    public ReportPageData buildReportPage(String period,
                                          List<Long> requestedProjectIds,
                                          boolean showInactive) {
        List<GitSource> sources = gitSourceRepository.findAll();
        List<TrackedProject> allProjects = trackedProjectRepository.findAll();
        List<TrackedUser> allUsers = trackedUserRepository.findAll();

        boolean hasSources = !sources.isEmpty();
        boolean hasProjects = !allProjects.isEmpty();
        boolean hasUsers = !allUsers.isEmpty();
        boolean onboardingMode = !hasProjects || !hasUsers;

        List<Long> activeJobIds = syncJobRepository
            .findByStatusOrderByStartedAtDesc(SyncStatus.STARTED)
            .stream().map(SyncJob::getId).toList();

        List<Map<String, Object>> usersWithAliases = buildUsersWithAliases(allUsers);

        if (onboardingMode) {
            return new ReportPageData(
                sources, hasSources, hasProjects, hasUsers, true,
                activeJobIds, usersWithAliases, allProjects,
                List.of(), period, showInactive,
                null, null, List.of(), Map.of()
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

        if (!userIds.isEmpty() && !selectedProjectIds.isEmpty()) {
            Map<Long, UserMetrics> current = metricCalculationService.calculate(
                selectedProjectIds, userIds, dateFrom, dateTo);
            Map<Long, UserMetrics> previous = metricCalculationService.calculate(
                selectedProjectIds, userIds, prevDateFrom, dateFrom);

            metrics = allUsers.stream()
                .map(u -> current.get(u.getId()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(UserMetrics::getMrMergedCount).reversed())
                .toList();

            deltas = buildDeltas(metrics, previous);
        }

        return new ReportPageData(
            sources, hasSources, hasProjects, hasUsers, false,
            activeJobIds, usersWithAliases, allProjects,
            selectedProjectIds, period, showInactive,
            dateFrom, dateTo, metrics, deltas
        );
    }

    private List<Map<String, Object>> buildUsersWithAliases(List<TrackedUser> users) {
        return users.stream()
            .map(u -> {
                List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(u.getId());
                Map<String, Object> entry = new HashMap<>();
                entry.put("user", u);
                entry.put("aliases", aliases);
                return entry;
            })
            .toList();
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<Long, Map<String, Number>> buildDeltas(List<UserMetrics> current,
                                                       Map<Long, UserMetrics> prev) {
        Map<Long, Map<String, Number>> result = new HashMap<>();
        for (UserMetrics m : current) {
            UserMetrics p = prev.get(m.getTrackedUserId());
            if (p == null) {
                continue;
            }
            Map<String, Number> d = new HashMap<>();
            d.put("mrMerged", m.getMrMergedCount() - p.getMrMergedCount());
            d.put("linesAdded", m.getLinesAdded() - p.getLinesAdded());
            d.put("linesDeleted", m.getLinesDeleted() - p.getLinesDeleted());
            d.put("commits", m.getCommitsInMrCount() - p.getCommitsInMrCount());
            d.put("comments", m.getReviewCommentsWrittenCount() - p.getReviewCommentsWrittenCount());
            d.put("reviewed", m.getMrsReviewedCount() - p.getMrsReviewedCount());
            d.put("approvals", m.getApprovalsGivenCount() - p.getApprovalsGivenCount());
            d.put("activeDays", m.getActiveDaysCount() - p.getActiveDaysCount());
            if (m.getAvgTimeToMergeMinutes() != null && p.getAvgTimeToMergeMinutes() != null) {
                d.put("timeToMerge", m.getAvgTimeToMergeMinutes() - p.getAvgTimeToMergeMinutes());
            }
            result.put(m.getTrackedUserId(), d);
        }
        return result;
    }

    private int parsePeriodDays(String period) {
        try {
            return PeriodType.valueOf(period).toDays();
        } catch (IllegalArgumentException e) {
            return PeriodType.LAST_30_DAYS.toDays();
        }
    }
}
