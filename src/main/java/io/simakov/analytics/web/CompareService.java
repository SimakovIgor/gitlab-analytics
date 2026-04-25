package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TeamProjectRepository;
import io.simakov.analytics.domain.repository.TeamRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompareService {

    private static final int CHART_WEEKS_MIN = 4;
    private static final int CHART_WEEKS_MAX = 52;
    // Flow: 0h → 100, 168h (1 week) → 0  — aligns with DORA "High" lead time threshold
    private static final double FLOW_MAX_HOURS = 168.0;
    // Deploy: 1/day = elite → 100; <1/month → 25
    private static final double DEPLOY_ELITE_PER_DAY = 1.0;
    private static final double DEPLOY_HIGH_PER_WEEK = 1.0;
    private static final double DEPLOY_MED_PER_MONTH = 1.0;
    // CFR: 0% → 100, 50%+ → 0  (15% is DORA "high" threshold, but we keep the bar visible above it)
    private static final double CFR_MAX = 0.50;

    private final TeamRepository teamRepository;
    private final TeamProjectRepository teamProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final MetricCalculationService metricCalculationService;
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
            return new OrgBenchmark(0, 0, 0, 0, -1, -1);
        }
        double avgLead = cards.stream()
            .mapToInt(TeamCardData::leadTimeHours).average().orElse(0);
        int totalMrs = cards.stream().mapToInt(TeamCardData::mrsCount).sum();
        double avgHealth = cards.stream()
            .mapToInt(TeamCardData::healthScore).average().orElse(0);

        // Sum deploys/day across teams that have release data (deploysPerWeek >= 0)
        double totalDeploysPerDay = cards.stream()
            .filter(c -> c.deploysPerWeek() >= 0)
            .mapToDouble(c -> c.deploysPerWeek() / 7.0)
            .sum();
        boolean hasDeployData = cards.stream().anyMatch(c -> c.deploysPerWeek() >= 0);

        // Avg CFR across teams that have CFR data
        OptionalDouble avgCfrOpt = cards.stream()
            .filter(c -> c.cfrPercent() >= 0)
            .mapToDouble(TeamCardData::cfrPercent)
            .average();

        return new OrgBenchmark(
            Math.round(avgLead * 10.0) / 10.0,
            totalMrs,
            Math.round(avgHealth * 10.0) / 10.0,
            cards.size(),
            hasDeployData ? Math.round(totalDeploysPerDay * 10.0) / 10.0 : -1,
            avgCfrOpt.isPresent() ? Math.round(avgCfrOpt.getAsDouble() * 10.0) / 10.0 : -1
        );
    }

    /**
     * Builds trend-chart JSON: { labels: [...], datasets: [{label, color, data: [...]}] }.
     * Health is computed fresh from MR data for each weekly 14-day window so the chart
     * shows genuine week-over-week dynamics rather than a smoothed 30-day rolling average.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String buildTrendChartJson(Long workspaceId,
                                      List<TeamCardData> cards,
                                      List<Long> selectedProjectIds,
                                      int periodDays) {
        if (cards.isEmpty()) {
            return "{\"labels\":[],\"datasets\":[]}";
        }

        int chartWeeks = Math.max(CHART_WEEKS_MIN, Math.min(CHART_WEEKS_MAX, periodDays / 7));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> weeks = new ArrayList<>();
        for (int i = chartWeeks - 1; i >= 0; i--) {
            weeks.add(today.minusWeeks(i));
        }

        List<String> labels = weeks.stream()
            .map(d -> d.getMonthValue() + "/" + d.getDayOfMonth())
            .toList();

        List<Map<String, Object>> series = new ArrayList<>();
        for (TeamCardData card : cards) {
            buildTeamSeries(workspaceId, card, selectedProjectIds, weeks).ifPresent(series::add);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("labels", labels);
        result.put("series", series);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"labels\":[],\"series\":[]}";
        }
    }

    private Optional<Map<String, Object>> buildTeamSeries(Long workspaceId,
                                                           TeamCardData card,
                                                           List<Long> selectedProjectIds,
                                                           List<LocalDate> weeks) {
        List<Long> memberIds = trackedUserRepository
            .findByWorkspaceIdAndTeamIdIn(workspaceId, List.of(card.teamId()))
            .stream().map(TrackedUser::getId).toList();
        if (memberIds.isEmpty()) {
            return Optional.empty();
        }

        List<Long> teamProjectIds = teamProjectRepository.findProjectIdsByTeamId(card.teamId());
        List<Long> effectiveProjectIds = teamProjectIds.isEmpty() ? selectedProjectIds : teamProjectIds;
        if (effectiveProjectIds.isEmpty()) {
            return Optional.empty();
        }

        List<Object> healthData = new ArrayList<>();
        List<Object> leadData = new ArrayList<>();
        List<Object> cfrData = new ArrayList<>();
        List<Object> deploysData = new ArrayList<>();
        List<Object> mrsData = new ArrayList<>();

        for (LocalDate week : weeks) {
            appendWeekPoint(week, memberIds, effectiveProjectIds, teamProjectIds,
                card.memberCount(), healthData, leadData, cfrData, deploysData, mrsData);
        }

        if (healthData.stream().allMatch(v -> v == null)) {
            return Optional.empty();
        }

        Map<String, Object> s = new HashMap<>();
        s.put("label", card.name());
        s.put("color", "var(--c-" + card.colorIndex() + ")");
        s.put("health", healthData);
        s.put("lead", leadData);
        s.put("cfr", cfrData);
        s.put("deploys", deploysData);
        s.put("mrs", mrsData);
        return Optional.of(s);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void appendWeekPoint(LocalDate week, List<Long> memberIds,
                                  List<Long> effectiveProjectIds, List<Long> teamProjectIds,
                                  int memberCount,
                                  List<Object> healthData, List<Object> leadData,
                                  List<Object> cfrData, List<Object> deploysData,
                                  List<Object> mrsData) {
        Instant weekTo = week.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant weekFrom = week.minusDays(13).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<Long, UserMetrics> metrics = metricCalculationService.calculate(
            effectiveProjectIds, memberIds, weekFrom, weekTo);
        healthData.add(metrics.isEmpty() ? null : weeklyTeamHealth(metrics.values(), memberCount));
        leadData.add(metrics.isEmpty() ? null : medianLeadHours(metrics.values()));

        int totalMrs = metrics.values().stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        mrsData.add(metrics.isEmpty() ? null : Math.round(totalMrs / 2.0 * 10.0) / 10.0);

        if (teamProjectIds.isEmpty()) {
            cfrData.add(null);
            deploysData.add(null);
        } else {
            long dep = releaseTagRepository.countProdDeploysInPeriodBetween(teamProjectIds, weekFrom, weekTo);
            long inc = jiraIncidentRepository.countIncidentsInPeriodBetween(teamProjectIds, weekFrom, weekTo);
            deploysData.add(Math.round(dep / 2.0 * 10.0) / 10.0);
            cfrData.add(dep > 0 ? Math.round(inc * 1000.0 / dep) / 10.0 : null);
        }
    }

    private static int weeklyTeamHealth(Collection<UserMetrics> metrics, int memberCount) {
        int effectiveMembers = Math.max(1, memberCount);
        int totalMrs = metrics.stream().mapToInt(UserMetrics::getMrMergedCount).sum();
        OptionalDouble ttm = metrics.stream()
            .filter(u -> u.getMedianTimeToMergeMinutes() != null)
            .mapToDouble(UserMetrics::getMedianTimeToMergeMinutes)
            .average();
        double avgRework = metrics.stream().mapToDouble(UserMetrics::getReworkRatio).average().orElse(0);
        int normalizedMrs = (int) Math.round(totalMrs * 90.0 / 14.0);
        int leadH = ttm.isPresent() ? (int) (ttm.getAsDouble() / 60.0) : 0;
        return healthScore(velocityScore(normalizedMrs, effectiveMembers), leadTimeScore(leadH), 50, 50, qualityScore(avgRework));
    }

    private static Double medianLeadHours(Collection<UserMetrics> metrics) {
        OptionalDouble ttm = metrics.stream()
            .filter(u -> u.getMedianTimeToMergeMinutes() != null)
            .mapToDouble(UserMetrics::getMedianTimeToMergeMinutes)
            .average();
        return ttm.isPresent() ? Math.round(ttm.getAsDouble() / 60.0 * 10.0) / 10.0 : null;
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

        // Preserve -1 sentinel so the template can show "—" for missing deploy/CFR data
        double displayDeploys = scores.deploysPerWeek < 0 ? -1 : Math.round(scores.deploysPerWeek * 10.0) / 10.0;
        double displayCfr = scores.cfrPercent < 0 ? -1 : Math.round(scores.cfrPercent * 10.0) / 10.0;
        return new TeamCardData(
            team.getId(), team.getName(), team.getColorIndex(), memberCount, projectCount,
            scores.velocity, scores.leadTime, scores.deploy, scores.cfr, scores.quality,
            scores.mrsCount, scores.leadTimeHours,
            displayDeploys, displayCfr,
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
            return -1; // no project data → neutral
        }
        if (deploys <= 0) {
            return -1; // no releases synced → can't compute CFR, show neutral (not perfect 0%)
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
        // 0h → 100, 168h (1 week) → 0 (linear)
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
        int teamCount,
        double deploysPerDay,   // -1 = no release data synced yet
        double avgCfrPercent    // -1 = no release data synced yet
    ) {
    }
}
