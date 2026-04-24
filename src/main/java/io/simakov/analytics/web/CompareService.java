package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TeamProjectRepository;
import io.simakov.analytics.domain.repository.TeamRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.Metric;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompareService {

    private static final int CHART_WEEKS = 13;
    // Flow: 0h → 100, 48h → 0
    private static final double FLOW_MAX_HOURS = 48.0;
    // Deploy: 1/day = elite → 100; <1/month → 25
    private static final double DEPLOY_ELITE_PER_DAY = 1.0;
    private static final double DEPLOY_HIGH_PER_WEEK = 1.0;
    private static final double DEPLOY_MED_PER_MONTH = 1.0;
    // CFR: 0% → 100, 15%+ → 0
    private static final double CFR_MAX = 0.15;

    private final TeamRepository teamRepository;
    private final TeamProjectRepository teamProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final MetricCalculationService metricCalculationService;
    private final MetricSnapshotRepository snapshotRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final JiraIncidentRepository jiraIncidentRepository;
    private final ObjectMapper objectMapper;

    // ── Main API ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamCardData> buildTeamCards(Long workspaceId,
                                             List<Long> selectedProjectIds,
                                             Instant dateFrom,
                                             Instant dateTo) {
        List<Team> teams = teamRepository.findByWorkspaceId(workspaceId);
        if (teams.isEmpty()) {
            return List.of();
        }

        List<Long> teamIds = teams.stream().map(Team::getId).toList();
        List<TrackedUser> allMembers = trackedUserRepository
            .findByWorkspaceIdAndTeamIdIn(workspaceId, teamIds);
        Map<Long, List<TrackedUser>> membersByTeam = allMembers.stream()
            .collect(Collectors.groupingBy(TrackedUser::getTeamId));

        long periodDays = ChronoUnit.DAYS.between(dateFrom, dateTo);
        Instant prevFrom = dateFrom.minus(periodDays, ChronoUnit.DAYS);
        Instant prevTo = dateFrom;

        List<TeamCardData> cards = new ArrayList<>();
        for (Team team : teams) {
            List<TrackedUser> members = membersByTeam.getOrDefault(team.getId(), List.of());
            List<Long> teamProjectIds = teamProjectRepository.findProjectIdsByTeamId(team.getId());
            // Use only projects that are in the current page filter
            List<Long> effectiveProjectIds = teamProjectIds.isEmpty()
                ? selectedProjectIds
                : teamProjectIds.stream().filter(selectedProjectIds::contains).toList();

            if (members.isEmpty()) {
                cards.add(emptyCard(team));
                continue;
            }
            List<Long> memberIds = members.stream().map(TrackedUser::getId).toList();

            Map<Long, UserMetrics> current = metricCalculationService
                .calculate(effectiveProjectIds, memberIds, dateFrom, dateTo);
            Map<Long, UserMetrics> previous = metricCalculationService
                .calculate(effectiveProjectIds, memberIds, prevFrom, prevTo);

            long deploys = teamProjectIds.isEmpty() ? -1L
                : releaseTagRepository.countProdDeploysInPeriodBetween(teamProjectIds, dateFrom, dateTo);
            long incidents = teamProjectIds.isEmpty() ? -1L
                : jiraIncidentRepository.countIncidentsInPeriodBetween(teamProjectIds, dateFrom, dateTo);
            long prevDeploys = teamProjectIds.isEmpty() ? -1L
                : releaseTagRepository.countProdDeploysInPeriodBetween(teamProjectIds, prevFrom, prevTo);
            long prevIncidents = teamProjectIds.isEmpty() ? -1L
                : jiraIncidentRepository.countIncidentsInPeriodBetween(teamProjectIds, prevFrom, prevTo);

            TeamCardData card = buildCard(team, members.size(), teamProjectIds.size(),
                current.values(), previous.values(),
                deploys, incidents, prevDeploys, prevIncidents, periodDays);
            cards.add(card);
        }

        cards.sort(Comparator.comparingInt(TeamCardData::healthScore).reversed());
        return cards;
    }

    public OrgBenchmark buildBenchmark(List<TeamCardData> cards) {
        if (cards.isEmpty()) {
            return new OrgBenchmark(0, 0, 0, 0);
        }
        double avgLead = cards.stream()
            .mapToInt(TeamCardData::leadTimeHours).average().orElse(0);
        int totalMrs = cards.stream().mapToInt(TeamCardData::mrsCount).sum();
        double avgHealth = cards.stream()
            .mapToInt(TeamCardData::healthScore).average().orElse(0);
        return new OrgBenchmark(
            Math.round(avgLead * 10.0) / 10.0,
            totalMrs,
            Math.round(avgHealth * 10.0) / 10.0,
            cards.size()
        );
    }

    /**
     * Returns JSON for Chart.js: { labels: [...], datasets: [{label, color, data: [...]}] }
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String buildTrendChartJson(Long workspaceId, List<TeamCardData> cards) {
        if (cards.isEmpty()) {
            return "{\"labels\":[],\"datasets\":[]}";
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> weeks = new ArrayList<>();
        for (int i = CHART_WEEKS - 1; i >= 0; i--) {
            weeks.add(today.minusWeeks(i));
        }

        List<String> labels = weeks.stream()
            .map(d -> d.getMonthValue() + "/" + d.getDayOfMonth())
            .toList();

        List<Map<String, Object>> datasets = new ArrayList<>();
        for (TeamCardData card : cards) {
            buildTeamDataset(workspaceId, card, weeks, today).ifPresent(datasets::add);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("labels", labels);
        result.put("datasets", datasets);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"labels\":[],\"datasets\":[]}";
        }
    }

    private Optional<Map<String, Object>> buildTeamDataset(Long workspaceId, TeamCardData card,
                                                            List<LocalDate> weeks, LocalDate today) {
        List<Long> memberIds = trackedUserRepository
            .findByWorkspaceIdAndTeamIdIn(workspaceId, List.of(card.teamId()))
            .stream().map(TrackedUser::getId).toList();
        if (memberIds.isEmpty()) {
            return Optional.empty();
        }

        LocalDate from = weeks.get(0).minusWeeks(1);
        List<MetricSnapshot> snapshots = snapshotRepository
            .findHistoryByWorkspace(workspaceId, memberIds, from, today);

        Map<LocalDate, List<MetricSnapshot>> byWeek = new LinkedHashMap<>();
        for (LocalDate week : weeks) {
            byWeek.put(week, new ArrayList<>());
        }
        for (MetricSnapshot s : snapshots) {
            LocalDate nearest = findNearestWeek(weeks, s.getSnapshotDate());
            if (nearest != null) {
                byWeek.get(nearest).add(s);
            }
        }

        List<Object> data = new ArrayList<>();
        for (LocalDate week : weeks) {
            List<MetricSnapshot> weekSnaps = byWeek.get(week);
            if (weekSnaps.isEmpty()) {
                data.add(null);
            } else {
                double avg = weekSnaps.stream()
                    .mapToInt(CompareService::healthFromSnapshot)
                    .average().orElse(0);
                data.add((int) Math.round(avg));
            }
        }

        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", card.name());
        dataset.put("color", "var(--c-" + card.colorIndex() + ")");
        dataset.put("data", data);
        return Optional.of(dataset);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:ParameterNumber")
    private TeamCardData buildCard(Team team, int memberCount, int projectCount,
                                   Collection<UserMetrics> current,
                                   Collection<UserMetrics> previous,
                                   long deploys, long incidents,
                                   long prevDeploys, long prevIncidents,
                                   long periodDays) {
        TeamScores scores = computeScores(memberCount, current, deploys, incidents, periodDays);
        TeamScores prevScores = computeScores(memberCount, previous, prevDeploys, prevIncidents, periodDays);

        int health = healthScore(scores.velocity, scores.leadTime, scores.deploy, scores.cfr, scores.quality);
        int prevHealth = healthScore(
            prevScores.velocity, prevScores.leadTime, prevScores.deploy, prevScores.cfr, prevScores.quality);

        String trend = computeTrend(health, prevHealth);

        return new TeamCardData(
            team.getId(), team.getName(), team.getColorIndex(), memberCount, projectCount,
            scores.velocity, scores.leadTime, scores.deploy, scores.cfr, scores.quality,
            scores.mrsCount, scores.leadTimeHours,
            scores.deploysPerWeek < 0 ? 0 : Math.round(scores.deploysPerWeek * 10.0) / 10.0,
            scores.cfrPercent < 0 ? 0 : Math.round(scores.cfrPercent * 10.0) / 10.0,
            health, trend
        );
    }

    private TeamScores computeScores(int memberCount, Collection<UserMetrics> metrics,
                                     long deploys, long incidents, long periodDays) {
        int mrsCount = metrics.stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        OptionalDouble medianTtm = metrics.stream()
            .filter(u -> u.getMedianTimeToMergeMinutes() != null)
            .mapToDouble(UserMetrics::getMedianTimeToMergeMinutes)
            .average();
        int leadTimeHours = medianTtm.isPresent()
            ? (int) Math.round(medianTtm.getAsDouble() / 60.0) : 0;
        double avgRework = metrics.stream().mapToDouble(UserMetrics::getReworkRatio).average().orElse(0);

        // -1 means no project data → neutral score
        double deploysPerWeek = deploys < 0 ? -1 : (double) deploys / Math.max(1, periodDays) * 7;
        double cfrPercent = computeCfr(deploys, incidents);

        return new TeamScores(
            mrsCount, leadTimeHours, deploysPerWeek, cfrPercent,
            velocityScore(mrsCount, memberCount),
            leadTimeScore(leadTimeHours),
            deployScore(deploysPerWeek, periodDays),
            cfrScore(cfrPercent),
            qualityScore(avgRework)
        );
    }

    private static double computeCfr(long deploys, long incidents) {
        if (incidents < 0) {
            return -1;
        }
        if (deploys <= 0) {
            return 0;
        }
        return (double) incidents / deploys * 100.0;
    }

    private static String computeTrend(int health, int prevHealth) {
        int delta = health - prevHealth;
        if (delta > 3) {
            return "up";
        }
        if (delta < -3) {
            return "down";
        }
        return "flat";
    }

    private TeamCardData emptyCard(Team team) {
        return new TeamCardData(
            team.getId(), team.getName(), team.getColorIndex(), 0, 0,
            0, 50, 50, 50, 50,
            0, 0, 0.0, 0.0,
            0, "flat"
        );
    }

    // Score formulas (0–100)

    private static int velocityScore(int mrs, int memberCount) {
        if (memberCount == 0) {
            return 0;
        }
        // 6 MRs/person/period = 100
        return (int) Math.min(100, (double) mrs / memberCount * 100.0 / 6.0);
    }

    private static int leadTimeScore(int leadHours) {
        // 0h → 100, 48h → 0 (linear)
        return (int) Math.max(0, 100 - leadHours * 100.0 / FLOW_MAX_HOURS);
    }

    private static int deployScore(double deploysPerWeek, long periodDays) {
        if (deploysPerWeek < 0) {
            return 50; // no project data → neutral
        }
        double perDay = deploysPerWeek / 7.0;
        if (perDay >= DEPLOY_ELITE_PER_DAY) {
            return 100;
        }
        if (deploysPerWeek >= DEPLOY_HIGH_PER_WEEK) {
            return 80;
        }
        // Less than 1/week
        double perMonth = deploysPerWeek * 30.0 / 7.0;
        if (perMonth >= DEPLOY_MED_PER_MONTH) {
            return 55;
        }
        return deploysPerWeek > 0 ? 25 : periodDays > 0 ? 10 : 50;
    }

    private static int cfrScore(double cfrPercent) {
        if (cfrPercent < 0) {
            return 50; // no project data → neutral
        }
        // 0% → 100, CFR_MAX (15%) → 0
        double ratio = cfrPercent / 100.0;
        return (int) Math.max(0, 100 - ratio * 100.0 / CFR_MAX);
    }

    private static int qualityScore(double reworkRatio) {
        // 0% rework → 100, 50%+ rework → 0
        return (int) Math.max(0, 100 - reworkRatio * 200.0);
    }

    static int healthScore(int velocity, int leadTime, int deploy, int cfr, int quality) {
        return (int) Math.round(
            velocity * 0.25
                + leadTime * 0.25
                + deploy * 0.25
                + cfr * 0.15
                + quality * 0.10
        );
    }

    private static int healthFromSnapshot(MetricSnapshot snapshot) {
        String json = snapshot.getMetricsJson();
        if (json == null || json.isBlank()) {
            return 50;
        }
        int mrs = extractInt(json, Metric.MR_MERGED_COUNT.key(), 0);
        double ttm = extractDouble(json, Metric.MEDIAN_TIME_TO_MERGE_MINUTES.key(), 0);
        double rework = extractDouble(json, Metric.REWORK_RATIO.key(), 0);
        int leadH = (int) (ttm / 60.0);
        return healthScore(velocityScore(mrs, 1), leadTimeScore(leadH), 50, 50, qualityScore(rework));
    }

    private static int extractInt(String json, String key, int defaultVal) {
        return (int) Math.round(extractDouble(json, key, defaultVal));
    }

    private static double extractDouble(String json, String key, double defaultVal) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return defaultVal;
        }
        int start = idx + search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
            || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static LocalDate findNearestWeek(List<LocalDate> weeks, LocalDate date) {
        if (weeks.isEmpty()) {
            return null;
        }
        LocalDate nearest = null;
        long minDiff = Long.MAX_VALUE;
        for (LocalDate week : weeks) {
            long diff = Math.abs(ChronoUnit.DAYS.between(date, week));
            if (diff < minDiff) {
                minDiff = diff;
                nearest = week;
            }
        }
        return minDiff <= 7 ? nearest : null;
    }

    // ── Records (inner types last) ────────────────────────────────────────────

    // Internal value object — not exposed outside the service
    private record TeamScores(
        int mrsCount,
        int leadTimeHours,
        double deploysPerWeek,
        double cfrPercent,
        int velocity,
        int leadTime,
        int deploy,
        int cfr,
        int quality
    ) {
    }

    // ── Public records ─────────────────────────────────────────────────────────

    public record TeamCardData(
        Long teamId,
        String name,
        int colorIndex,
        int memberCount,
        int projectCount,
        // Radar axes 0–100
        int velocityScore,
        int leadTimeScore,
        int deployScore,
        int cfrScore,
        int qualityScore,
        // Raw values for mini-bars
        int mrsCount,
        int leadTimeHours,
        double deploysPerWeek,
        double cfrPercent,
        // Composite
        int healthScore,
        String trend     // "up" | "flat" | "down"
    ) {
    }

    public record OrgBenchmark(
        double avgLeadTimeHours,
        int totalMrs,
        double avgHealthScore,
        int teamCount
    ) {
    }
}
