package io.simakov.analytics.digest;

import io.simakov.analytics.digest.DigestData.ContributorRow;
import io.simakov.analytics.digest.DigestData.InsightRow;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.insights.InsightService;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private static final int TOP_CONTRIBUTORS = 5;
    private static final int TOP_INSIGHTS = 5;
    private static final int DIGEST_DAYS = 7;
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM", new Locale("ru")).withZone(ZoneOffset.UTC);

    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final AppUserRepository appUserRepository;
    private final MetricCalculationService metricCalculationService;
    private final ReleaseTagRepository releaseTagRepository;
    private final InsightService insightService;

    @Value("${app.base-url:http://localhost:8080}")
    private String appUrl;

    /**
     * Builds digest data for a workspace for the last 7 days.
     * Returns empty if workspace has no projects or no tracked users.
     */
    @Transactional(readOnly = true)
    public Optional<DigestData> build(Workspace workspace) {
        List<TrackedProject> projects = trackedProjectRepository.findAllByWorkspaceId(workspace.getId());
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspace.getId());

        if (projects.isEmpty() || users.isEmpty()) {
            log.debug("Skipping digest for workspace={} — no projects or users", workspace.getId());
            return Optional.empty();
        }

        Optional<AppUser> ownerOpt = appUserRepository.findById(workspace.getOwnerId());
        if (ownerOpt.isEmpty()) {
            log.warn("Skipping digest for workspace={} — owner not found", workspace.getId());
            return Optional.empty();
        }
        AppUser owner = ownerOpt.get();

        List<Long> projectIds = projects.stream().map(TrackedProject::getId).toList();
        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();

        Instant now = Instant.now();
        Instant weekStart = now.minus(DIGEST_DAYS, ChronoUnit.DAYS);
        Instant prevWeekStart = weekStart.minus(DIGEST_DAYS, ChronoUnit.DAYS);

        Map<Long, UserMetrics> currentMetrics = metricCalculationService.calculate(projectIds, userIds, weekStart, now);
        Map<Long, UserMetrics> prevMetrics = metricCalculationService.calculate(projectIds, userIds, prevWeekStart, weekStart);

        int mrCount = currentMetrics.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        int prevMrCount = prevMetrics.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();

        Double ttmMedian = teamTtmMedian(currentMetrics);
        Double prevTtmMedian = teamTtmMedian(prevMetrics);

        long deploys = releaseTagRepository.countProdDeploysInPeriodBetween(projectIds, weekStart, now);

        List<ContributorRow> topContributors = buildTopContributors(users, currentMetrics);
        List<InsightRow> insights = buildInsights(workspace.getId(), projectIds);

        String periodLabel = DATE_FMT.format(weekStart) + " — " + DATE_FMT.format(now);

        return Optional.of(new DigestData(
            workspace.getName(),
            owner.getName(),
            owner.getEmail(),
            periodLabel,
            appUrl,
            mrCount,
            prevMrCount,
            ttmMedian,
            prevTtmMedian,
            (int) deploys,
            topContributors,
            insights
        ));
    }

    private Double teamTtmMedian(Map<Long, UserMetrics> metrics) {
        List<Double> values = metrics.values().stream()
            .map(UserMetrics::getMedianTimeToMergeMinutes)
            .filter(v -> v != null && v > 0)
            .toList();
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        double median = sorted.size() % 2 == 0
            ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0
            : sorted.get(mid);
        return median / 60.0; // minutes → hours
    }

    private List<ContributorRow> buildTopContributors(List<TrackedUser> users,
                                                       Map<Long, UserMetrics> metrics) {
        Map<Long, String> nameById = users.stream()
            .collect(java.util.stream.Collectors.toMap(TrackedUser::getId, TrackedUser::getEmail));

        return metrics.entrySet().stream()
            .filter(e -> e.getValue().getMrMergedCount() > 0)
            .sorted(Comparator.comparingInt((Map.Entry<Long, UserMetrics> e) -> e.getValue().getMrMergedCount())
                .reversed())
            .limit(TOP_CONTRIBUTORS)
            .map(e -> {
                String name = nameById.getOrDefault(e.getKey(), "—");
                Double ttmH = e.getValue().getMedianTimeToMergeMinutes() != null
                    ? e.getValue().getMedianTimeToMergeMinutes() / 60.0
                    : null;
                return new ContributorRow(name, e.getValue().getMrMergedCount(), ttmH);
            })
            .toList();
    }

    private List<InsightRow> buildInsights(Long workspaceId, List<Long> projectIds) {
        try {
            return insightService.evaluate(workspaceId, "LAST_7_DAYS", projectIds).stream()
                .sorted(Comparator.comparingInt(TeamInsight::severity).reversed())
                .limit(TOP_INSIGHTS)
                .map(i -> new InsightRow(i.kind().name(), i.title()))
                .toList();
        } catch (Exception e) {
            log.warn("Failed to evaluate insights for workspace={}: {}", workspaceId, e.getMessage());
            return List.of();
        }
    }
}
