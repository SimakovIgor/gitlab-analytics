package io.simakov.analytics.digest;

import io.simakov.analytics.digest.DigestData.ContributorRow;
import io.simakov.analytics.digest.DigestData.InsightRow;
import io.simakov.analytics.digest.DigestData.TeamSection;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TeamRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private static final int TOP_CONTRIBUTORS_PER_TEAM = 3;
    private static final int TOP_INSIGHTS = 5;
    private static final int DIGEST_DAYS = 7;
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM", new Locale("ru")).withZone(ZoneOffset.UTC);

    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final AppUserRepository appUserRepository;
    private final TeamRepository teamRepository;
    private final MetricCalculationService metricCalculationService;
    private final ReleaseTagRepository releaseTagRepository;
    private final InsightService insightService;

    @Value("${app.base-url:http://localhost:8080}")
    private String appUrl;

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

        List<Long> projectIds = projects.stream().map(TrackedProject::getId).toList();
        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();

        Instant now = Instant.now();
        Instant weekStart = now.minus(DIGEST_DAYS, ChronoUnit.DAYS);
        Instant prevWeekStart = weekStart.minus(DIGEST_DAYS, ChronoUnit.DAYS);

        Map<Long, UserMetrics> current = metricCalculationService.calculate(projectIds, userIds, weekStart, now);
        Map<Long, UserMetrics> prev = metricCalculationService.calculate(projectIds, userIds, prevWeekStart, weekStart);

        int mrCount = current.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        int prevMrCount = prev.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        Double ttmMedian = teamTtmMedian(current);
        Double prevTtmMedian = teamTtmMedian(prev);
        long deploys = releaseTagRepository.countProdDeploysInPeriodBetween(projectIds, weekStart, now);

        List<TeamSection> teamSections = buildTeamSections(workspace.getId(), users, current, prev);
        List<InsightRow> insights = buildInsights(workspace.getId(), projectIds);

        String periodLabel = DATE_FMT.format(weekStart) + " — " + DATE_FMT.format(now);
        AppUser owner = ownerOpt.get();

        return Optional.of(new DigestData(
            workspace.getName(),
            owner.getName(),
            owner.getEmail(),
            periodLabel,
            appUrl,
            mrCount, prevMrCount,
            ttmMedian, prevTtmMedian,
            (int) deploys,
            teamSections,
            insights
        ));
    }

    private List<TeamSection> buildTeamSections(Long workspaceId,
                                                 List<TrackedUser> users,
                                                 Map<Long, UserMetrics> current,
                                                 Map<Long, UserMetrics> prev) {
        List<Team> teams = teamRepository.findByWorkspaceId(workspaceId);
        if (teams.isEmpty()) {
            return List.of();
        }

        Map<Long, String> userNameById = users.stream()
            .collect(Collectors.toMap(TrackedUser::getId, TrackedUser::getEmail));

        List<TeamSection> sections = new ArrayList<>();
        for (Team team : teams) {
            TeamSection section = buildSingleTeamSection(team, users, userNameById, current, prev);
            if (section != null) {
                sections.add(section);
            }
        }
        addUnassignedSection(sections, users, userNameById, current, prev);
        return sections;
    }

    private TeamSection buildSingleTeamSection(Team team,
                                                List<TrackedUser> users,
                                                Map<Long, String> userNameById,
                                                Map<Long, UserMetrics> current,
                                                Map<Long, UserMetrics> prev) {
        List<Long> teamUserIds = users.stream()
            .filter(u -> team.getId().equals(u.getTeamId()))
            .map(TrackedUser::getId)
            .toList();
        if (teamUserIds.isEmpty()) {
            return null;
        }
        Map<Long, UserMetrics> teamCurrent = filterByIds(current, teamUserIds);
        Map<Long, UserMetrics> teamPrev = filterByIds(prev, teamUserIds);
        int teamMrCount = teamCurrent.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        int teamPrevMrCount = teamPrev.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        List<ContributorRow> topContributors = buildTopContributors(teamCurrent, userNameById);
        return new TeamSection(
            team.getName(), team.getColorIndex(),
            teamMrCount, teamPrevMrCount,
            teamTtmMedian(teamCurrent), teamTtmMedian(teamPrev),
            topContributors
        );
    }

    private void addUnassignedSection(List<TeamSection> sections,
                                       List<TrackedUser> users,
                                       Map<Long, String> userNameById,
                                       Map<Long, UserMetrics> current,
                                       Map<Long, UserMetrics> prev) {
        List<Long> unassignedIds = users.stream()
            .filter(u -> u.getTeamId() == null)
            .map(TrackedUser::getId)
            .toList();
        if (unassignedIds.isEmpty()) {
            return;
        }
        Map<Long, UserMetrics> unassignedCurrent = filterByIds(current, unassignedIds);
        int unassignedMr = unassignedCurrent.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        if (unassignedMr > 0) {
            Map<Long, UserMetrics> unassignedPrev = filterByIds(prev, unassignedIds);
            sections.add(new TeamSection(
                "Без команды", 0,
                unassignedMr,
                unassignedPrev.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum(),
                teamTtmMedian(unassignedCurrent), teamTtmMedian(unassignedPrev),
                buildTopContributors(unassignedCurrent, userNameById)
            ));
        }
    }

    private Map<Long, UserMetrics> filterByIds(Map<Long, UserMetrics> metrics, List<Long> ids) {
        return metrics.entrySet().stream()
            .filter(e -> ids.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<ContributorRow> buildTopContributors(Map<Long, UserMetrics> metrics,
                                                       Map<Long, String> userNameById) {
        return metrics.entrySet().stream()
            .filter(e -> e.getValue().getMrMergedCount() > 0)
            .sorted(Comparator.comparingInt((Map.Entry<Long, UserMetrics> e)
                -> e.getValue().getMrMergedCount()).reversed())
            .limit(TOP_CONTRIBUTORS_PER_TEAM)
            .map(e -> {
                String name = userNameById.getOrDefault(e.getKey(), "—");
                if (name.contains("@")) {
                    name = name.substring(0, name.indexOf('@'));
                }
                Double ttmH = e.getValue().getMedianTimeToMergeMinutes() != null
                    ? e.getValue().getMedianTimeToMergeMinutes() / 60.0
                    : null;
                return new ContributorRow(name, e.getValue().getMrMergedCount(), ttmH);
            })
            .toList();
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
        return median / 60.0;
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
