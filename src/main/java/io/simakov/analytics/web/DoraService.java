package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.DeployFrequencyWeekProjection;
import io.simakov.analytics.domain.repository.IncidentWeekProjection;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MttrWeekProjection;
import io.simakov.analytics.domain.repository.RealLeadTimeSummaryProjection;
import io.simakov.analytics.domain.repository.RealLeadTimeWeekProjection;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.model.DoraMetric;
import io.simakov.analytics.dora.model.DoraRating;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.GodClass")
public class DoraService {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final MergeRequestRepository mergeRequestRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final JiraIncidentRepository jiraIncidentRepository;
    private final ObjectMapper objectMapper;

    private static double round(Number value) {
        if (value == null) {
            return 0.0;
        }
        return Math.round(value.doubleValue() * 10.0) / 10.0;
    }

    public List<TrackedProject> getAllProjects() {
        return trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(WorkspaceContext.get());
    }

    /**
     * Реальный Lead Time for Changes: от создания MR до деплоя в прод через release_tag.
     * Учитываются только MR с заполненным prod_deployed_at.
     * Ключи возвращаемой map: totalMrs, medianDays, p75Days, p95Days, leadTimeRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildLeadTimeData(List<Long> projectIds,
                                                 int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        List<RealLeadTimeSummaryProjection> summaryRows =
            mergeRequestRepository.findRealLeadTimeSummary(resolvedIds, dateFrom);
        RealLeadTimeSummaryProjection summary = summaryRows.isEmpty()
            ? null
            : summaryRows.getFirst();
        List<RealLeadTimeWeekProjection> weekly =
            mergeRequestRepository.findRealLeadTimeByWeek(resolvedIds, dateFrom);

        Double medianDays = summary != null && summary.getMedianDays() != null
            ? round(summary.getMedianDays())
            : null;
        DoraRating leadTimeRating = DoraMetric.LEAD_TIME_FOR_CHANGES.computeRating(medianDays);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMrs", summary != null
            ? summary.getMrCount().intValue()
            : 0);
        result.put("medianDays", medianDays);
        result.put("p75Days", summary != null && summary.getP75Days() != null
            ? round(summary.getP75Days())
            : null);
        result.put("p95Days", summary != null && summary.getP95Days() != null
            ? round(summary.getP95Days())
            : null);
        result.put("leadTimeRating", leadTimeRating);
        result.put("chartJson", buildRealLeadTimeChartJson(weekly));
        return result;
    }

    /**
     * Deployment Frequency: prod deploys per day over the given period.
     * Ключи: totalDeploys, deploysPerDay, displayValue, displayUnit, deployFreqRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildDeployFrequencyData(List<Long> projectIds,
                                                        int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        long totalDeploys = releaseTagRepository.countProdDeploysInPeriod(resolvedIds, dateFrom);
        double deploysPerDay = days > 0
            ? (double) totalDeploys / days
            : 0.0;

        DoraRating rating = DoraMetric.DEPLOYMENT_FREQUENCY.computeRating(
            totalDeploys == 0
                ? null
                : deploysPerDay);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDeploys", totalDeploys);
        result.put("deploysPerDay", round(deploysPerDay));
        result.put("displayValue", formatDeployFrequency(deploysPerDay));
        result.put("displayUnit", formatDeployFrequencyUnit(deploysPerDay));
        result.put("deployFreqRating", rating);
        result.put("chartJson", buildDeployFrequencyChartJson(resolvedIds, dateFrom));
        return result;
    }

    /**
     * Change Failure Rate: incidents / deployments * 100 over the given period.
     * Ключи: totalIncidents, totalDeploys, cfrPercent, cfrRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildChangeFailureRateData(List<Long> projectIds,
                                                          int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        long totalIncidents = jiraIncidentRepository.countIncidentsInPeriod(resolvedIds, dateFrom);
        long totalDeploys = releaseTagRepository.countProdDeploysInPeriod(resolvedIds, dateFrom);

        Double cfrPercent = totalDeploys > 0
            ? round(totalIncidents * 100.0 / totalDeploys)
            : null;

        DoraRating rating = DoraMetric.CHANGE_FAILURE_RATE.computeRating(cfrPercent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalIncidents", totalIncidents);
        result.put("totalDeploys", totalDeploys);
        result.put("cfrPercent", cfrPercent);
        result.put("cfrRating", rating);
        result.put("chartJson", buildCfrChartJson(resolvedIds, dateFrom));
        return result;
    }

    private String buildCfrChartJson(List<Long> projectIds,
                                     Instant dateFrom) {
        List<DeployFrequencyWeekProjection> deployWeeks =
            releaseTagRepository.countProdDeploysByWeek(projectIds, dateFrom);
        List<IncidentWeekProjection> incidentWeeks =
            jiraIncidentRepository.countIncidentsByWeek(projectIds, dateFrom);

        Map<String, Long> deploysByWeek = new LinkedHashMap<>();
        for (DeployFrequencyWeekProjection row : deployWeeks) {
            deploysByWeek.put(row.getWeekLabel(), row.getDeployCount());
        }
        Map<String, Long> incidentsByWeek = new LinkedHashMap<>();
        for (IncidentWeekProjection row : incidentWeeks) {
            incidentsByWeek.put(row.getWeekLabel(), row.getIncidentCount());
        }

        // Merge all week labels in order
        Map<String, Double> cfrByWeek = new LinkedHashMap<>();
        for (String week : deploysByWeek.keySet()) {
            long deploys = deploysByWeek.getOrDefault(week, 0L);
            long incidents = incidentsByWeek.getOrDefault(week, 0L);
            cfrByWeek.put(week, deploys > 0
                ? round(incidents * 100.0 / deploys)
                : 0.0);
        }
        // Add weeks that have incidents but no deploys (CFR would be infinite, show 0)
        for (String week : incidentsByWeek.keySet()) {
            cfrByWeek.putIfAbsent(week, 0.0);
        }

        List<String> labels = new ArrayList<>(cfrByWeek.keySet());
        List<Double> values = new ArrayList<>(cfrByWeek.values());

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "datasets", List.of(
                Map.of(
                    "label", "CFR (%)",
                    "data", values,
                    "borderColor", "#ef4444",
                    "backgroundColor", "rgba(239,68,68,0.12)",
                    "fill", true,
                    "tension", 0.3,
                    "pointRadius", 3
                )
            )
        );

        try {
            return objectMapper.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            log.warn("Ошибка сериализации данных CFR графика", e);
            return "{}";
        }
    }

    /**
     * MTTR (Mean Time To Recovery): average hours from impact start to impact end.
     * Ключи: mttrHours, totalIncidents, mttrRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildMttrData(List<Long> projectIds,
                                             int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        Double avgHours = jiraIncidentRepository.findAvgMttrHours(resolvedIds, dateFrom);
        long totalIncidents = jiraIncidentRepository.countIncidentsWithImpact(resolvedIds, dateFrom);

        Double mttrHours = avgHours != null
            ? round(avgHours)
            : null;
        DoraRating rating = DoraMetric.MTTR.computeRating(mttrHours);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mttrHours", mttrHours);
        result.put("totalIncidents", totalIncidents);
        result.put("mttrRating", rating);
        result.put("chartJson", buildMttrChartJson(resolvedIds, dateFrom));
        return result;
    }

    private String buildMttrChartJson(List<Long> projectIds,
                                      Instant dateFrom) {
        List<MttrWeekProjection> weekly = jiraIncidentRepository.findMttrByWeek(projectIds, dateFrom);

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (MttrWeekProjection row : weekly) {
            labels.add(row.getWeekLabel());
            values.add(row.getAvgHours() != null
                ? round(row.getAvgHours())
                : 0.0);
        }

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "datasets", List.of(
                Map.of(
                    "label", "MTTR (ч)",
                    "data", values,
                    "borderColor", "#f59e0b",
                    "backgroundColor", "rgba(245,158,11,0.12)",
                    "fill", true,
                    "tension", 0.3,
                    "pointRadius", 3
                )
            )
        );

        try {
            return objectMapper.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            log.warn("Ошибка сериализации данных MTTR графика", e);
            return "{}";
        }
    }

    private String formatDeployFrequency(double deploysPerDay) {
        if (deploysPerDay >= 1.0) {
            return String.valueOf(round(deploysPerDay));
        }
        if (deploysPerDay >= 1.0 / 7) {
            return String.valueOf(round(deploysPerDay * 7));
        }
        return String.valueOf(round(deploysPerDay * 30));
    }

    private String formatDeployFrequencyUnit(double deploysPerDay) {
        if (deploysPerDay >= 1.0) {
            return "/ день";
        }
        if (deploysPerDay >= 1.0 / 7) {
            return "/ неделю";
        }
        return "/ месяц";
    }

    private String buildDeployFrequencyChartJson(List<Long> projectIds,
                                                 Instant dateFrom) {
        List<DeployFrequencyWeekProjection> weekly =
            releaseTagRepository.countProdDeploysByWeek(projectIds, dateFrom);

        List<String> labels = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (DeployFrequencyWeekProjection row : weekly) {
            labels.add(row.getWeekLabel());
            counts.add(row.getDeployCount());
        }

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "datasets", List.of(
                Map.of(
                    "label", "Деплои",
                    "data", counts,
                    "backgroundColor", "rgba(99,102,241,0.5)",
                    "borderColor", "#6366f1",
                    "borderWidth", 1,
                    "borderRadius", 4
                )
            )
        );

        try {
            return objectMapper.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            log.warn("Ошибка сериализации данных Deploy Frequency графика", e);
            return "{}";
        }
    }

    /**
     * Returns releases with MR count and real Lead Time (mr.created_at → prod_deployed_at).
     * Only includes releases that have been attributed to at least one MR, or have a prod deploy timestamp.
     */
    @Transactional(readOnly = true)
    public List<ReleaseRowDto> buildReleasesData(List<Long> projectIds) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<ReleaseTag> tags = releaseTagRepository.findAllByProjectIdsOrderByCreatedAtDesc(resolvedIds)
            .stream()
            .sorted(Comparator.comparing(this::effectiveWindowTime).reversed())
            .toList();

        if (tags.isEmpty()) {
            return List.of();
        }

        Map<Long, String> projectNameById = trackedProjectRepository.findAllById(resolvedIds).stream()
            .collect(Collectors.toMap(TrackedProject::getId, TrackedProject::getName));

        // Load all MRs that have a release attribution for these projects
        List<Long> tagIds = tags.stream().map(ReleaseTag::getId).toList();
        List<MergeRequest> allAttributedMrs = mergeRequestRepository
            .findOpenByProjectIds(resolvedIds, MrState.MERGED)
            .stream()
            .filter(mr -> mr.getReleaseTagId() != null && tagIds.contains(mr.getReleaseTagId()))
            .toList();

        Map<Long, List<MergeRequest>> mrsByTag = allAttributedMrs.stream()
            .collect(Collectors.groupingBy(MergeRequest::getReleaseTagId));

        List<ReleaseRowDto> rows = new ArrayList<>();
        for (ReleaseTag tag : tags) {
            List<MergeRequest> tagMrs = mrsByTag.getOrDefault(tag.getId(), List.of());
            rows.add(new ReleaseRowDto(
                tag.getId(),
                tag.getTagName(),
                projectNameById.getOrDefault(tag.getTrackedProjectId(), "—"),
                DATE_FMT.format(effectiveWindowTime(tag)),
                tag.getProdDeployedAt() != null
                    ? DATETIME_FMT.format(tag.getProdDeployedAt())
                    : null,
                tagMrs.size(),
                computeMedianLeadTimeDays(tag, tagMrs),
                buildMrRows(tagMrs)
            ));
        }
        return rows;
    }

    private Double computeMedianLeadTimeDays(ReleaseTag tag,
                                             List<MergeRequest> tagMrs) {
        if (tag.getProdDeployedAt() == null || tagMrs.isEmpty()) {
            return null;
        }
        List<Double> sorted = tagMrs.stream()
            .filter(mr -> mr.getCreatedAtGitlab() != null)
            .mapToDouble(mr -> {
                long seconds = tag.getProdDeployedAt().getEpochSecond()
                    - mr.getCreatedAtGitlab().getEpochSecond();
                return Math.round(seconds / 3600.0 / 24.0 * 10.0) / 10.0;
            })
            .sorted()
            .boxed()
            .toList();
        if (sorted.isEmpty()) {
            return null;
        }
        return sorted.get(sorted.size() / 2);
    }

    private List<MrRowDto> buildMrRows(List<MergeRequest> tagMrs) {
        return tagMrs.stream()
            .sorted((a, b) -> {
                if (a.getMergedAtGitlab() == null) {
                    return 1;
                }
                if (b.getMergedAtGitlab() == null) {
                    return -1;
                }
                return b.getMergedAtGitlab().compareTo(a.getMergedAtGitlab());
            })
            .map(mr -> new MrRowDto(
                mr.getGitlabMrIid(),
                mr.getTitle(),
                mr.getWebUrl(),
                mr.getAuthorName(),
                mr.getMergedAtGitlab() != null
                    ? "merged " + DATE_FMT.format(mr.getMergedAtGitlab())
                    : null))
            .toList();
    }

    /**
     * Effective chronological boundary for a release tag.
     * Uses min(tagCreatedAt, prodDeployedAt) to handle retroactively created releases.
     */
    private Instant effectiveWindowTime(ReleaseTag tag) {
        if (tag.getProdDeployedAt() != null && tag.getProdDeployedAt().isBefore(tag.getTagCreatedAt())) {
            return tag.getProdDeployedAt();
        }
        return tag.getTagCreatedAt();
    }

    private List<Long> resolveProjectIds(List<Long> requested) {
        if (requested != null) {
            return requested;
        }
        return trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(WorkspaceContext.get()).stream()
            .map(TrackedProject::getId)
            .toList();
    }

    private String buildRealLeadTimeChartJson(List<RealLeadTimeWeekProjection> rows) {
        List<String> labels = new ArrayList<>();
        List<Double> medians = new ArrayList<>();
        List<Double> p75s = new ArrayList<>();

        for (RealLeadTimeWeekProjection row : rows) {
            labels.add(row.getPeriod().toString());
            medians.add(round(row.getMedianDays()));
            p75s.add(round(row.getP75Days()));
        }

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "datasets", List.of(
                Map.of(
                    "label", "Медиана (дн)",
                    "data", medians,
                    "borderColor", "#f59e0b",
                    "backgroundColor", "rgba(245,158,11,0.08)",
                    "fill", true,
                    "tension", 0.3,
                    "pointRadius", 3
                ),
                Map.of(
                    "label", "P75 (дн)",
                    "data", p75s,
                    "borderColor", "#94a3b8",
                    "backgroundColor", "transparent",
                    "borderDash", List.of(4, 4),
                    "tension", 0.3,
                    "pointRadius", 2
                )
            )
        );

        try {
            return objectMapper.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            log.warn("Ошибка сериализации данных DORA-графика", e);
            return "{}";
        }
    }

    /**
     * Returns DORA Lead Time median (days) for MRs deployed in [dateFrom, dateTo).
     * Returns null if no data available.
     */
    @Transactional(readOnly = true)
    public Double computeLeadTimeMedianDays(List<Long> projectIds,
                                            Instant dateFrom,
                                            Instant dateTo) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<RealLeadTimeSummaryProjection> rows =
            mergeRequestRepository.findRealLeadTimeSummaryBetween(resolvedIds, dateFrom, dateTo);
        if (rows.isEmpty() || rows.getFirst().getMedianDays() == null) {
            return null;
        }
        return round(rows.getFirst().getMedianDays());
    }

    /**
     * Returns DORA Deploy Frequency (deploys/day) for the period [dateFrom, dateTo).
     * Returns null if no deploys found.
     */
    @Transactional(readOnly = true)
    public Double computeDeploysPerDay(List<Long> projectIds,
                                       Instant dateFrom,
                                       Instant dateTo,
                                       int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        long total = releaseTagRepository.countProdDeploysInPeriodBetween(resolvedIds, dateFrom, dateTo);
        if (total == 0) {
            return null;
        }
        return round((double) total / days);
    }

    /**
     * Returns average MTTR (hours) for incidents with valid impact times in [dateFrom, dateTo).
     * Returns null if no data available.
     */
    @Transactional(readOnly = true)
    public Double computeMttrHours(List<Long> projectIds,
                                    Instant dateFrom) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Double avgHours = jiraIncidentRepository.findAvgMttrHours(resolvedIds, dateFrom);
        if (avgHours == null) {
            return null;
        }
        return round(avgHours);
    }

    public record ReleaseRowDto(
        Long id,
        String tagName,
        String projectName,
        String tagCreatedAtDisplay,
        String prodDeployedAtDisplay,
        int mrCount,
        Double medianLeadTimeDays,
        List<MrRowDto> mrs
    ) {

    }

    public record MrRowDto(
        Long mrIid,
        String title,
        String webUrl,
        String authorName,
        String mergedAtDisplay
    ) {

    }
}
