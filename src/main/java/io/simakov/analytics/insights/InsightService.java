package io.simakov.analytics.insights;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.DoraService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates all {@link InsightEvaluator} beans to produce the full list of team insights.
 * <p>
 * Evaluators are collected automatically by Spring via {@code List<InsightEvaluator>} injection
 * (Strategy pattern — add a new {@code @Component} implementing the interface to register a rule).
 */
@Service
@RequiredArgsConstructor
public class InsightService {

    private final List<InsightEvaluator> evaluators;
    private final MetricCalculationService metricCalculationService;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final DoraService doraService;

    /**
     * Evaluates all insight rules for the given workspace and period.
     *
     * @param workspaceId        the current workspace
     * @param period             period type string (e.g. "LAST_30_DAYS")
     * @param selectedProjectIds project filter; if null or empty, all workspace projects are used
     * @return insights sorted by severity descending
     */
    @Transactional(readOnly = true)
    public List<TeamInsight> evaluate(Long workspaceId,
                                      String period,
                                      List<Long> selectedProjectIds) {
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);
        if (users.isEmpty()) {
            return List.of();
        }

        List<Long> projectIds = resolveProjectIds(workspaceId, selectedProjectIds);
        if (projectIds.isEmpty()) {
            return List.of();
        }

        int days = PeriodType.valueOf(period).toDays();
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, days);
        Instant prevDateFrom = DateTimeUtils.minusDays(dateFrom, days);

        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();

        Map<Long, UserMetrics> current = metricCalculationService.calculate(projectIds, userIds, dateFrom, dateTo);
        Map<Long, UserMetrics> previous = metricCalculationService.calculate(projectIds, userIds, prevDateFrom, dateFrom);

        List<MergeRequest> openMrs = mergeRequestRepository.findOpenByProjectIds(projectIds, MrState.OPENED);

        Map<Long, Long> gitlabUserIdToTrackedUserId = buildGitlabUserIdMap(userIds);

        // DORA metrics for current and previous periods
        Double leadTimeMedianDays = doraService.computeLeadTimeMedianDays(projectIds, dateFrom, dateTo);
        Double prevLeadTimeMedianDays = doraService.computeLeadTimeMedianDays(projectIds, prevDateFrom, dateFrom);
        Double deploysPerDay = doraService.computeDeploysPerDay(projectIds, dateFrom, dateTo, days);
        Double prevDeploysPerDay = doraService.computeDeploysPerDay(projectIds, prevDateFrom, dateFrom, days);

        InsightContext ctx = new InsightContext(
            users, current, previous, openMrs, gitlabUserIdToTrackedUserId,
            leadTimeMedianDays, prevLeadTimeMedianDays, deploysPerDay, prevDeploysPerDay
        );

        return evaluators.stream()
            .flatMap(e -> e.evaluate(ctx).stream())
            .sorted(Comparator.comparingInt(TeamInsight::severity).reversed())
            .collect(Collectors.toList());
    }

    private List<Long> resolveProjectIds(Long workspaceId,
                                         List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedProjectRepository.findAllByWorkspaceId(workspaceId)
            .stream()
            .map(TrackedProject::getId)
            .toList();
    }

    private Map<Long, Long> buildGitlabUserIdMap(List<Long> userIds) {
        return aliasRepository.findByTrackedUserIdIn(userIds).stream()
            .filter(a -> a.getGitlabUserId() != null)
            .collect(Collectors.toMap(
                TrackedUserAlias::getGitlabUserId,
                TrackedUserAlias::getTrackedUserId,
                (existing, replacement) -> existing
            ));
    }
}
