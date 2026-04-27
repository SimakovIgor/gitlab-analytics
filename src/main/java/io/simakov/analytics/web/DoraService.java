package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.DoraServiceMapping;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.DeployFrequencyWeekProjection;
import io.simakov.analytics.domain.repository.DoraDeployEventRepository;
import io.simakov.analytics.domain.repository.DoraIncidentEventRepository;
import io.simakov.analytics.domain.repository.DoraServiceMappingRepository;
import io.simakov.analytics.domain.repository.IncidentWeekProjection;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MttrIncidentProjection;
import io.simakov.analytics.domain.repository.RealLeadTimeSummaryProjection;
import io.simakov.analytics.domain.repository.RealLeadTimeWeekProjection;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.model.DataQualityState;
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
@SuppressWarnings({"PMD.GodClass", "PMD.CyclomaticComplexity"})
public class DoraService {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final MergeRequestRepository mergeRequestRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final DoraDeployEventRepository doraDeployEventRepository;
    private final DoraIncidentEventRepository doraIncidentEventRepository;
    private final DoraServiceMappingRepository doraServiceMappingRepository;
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
     * Reads from the universal dora_deploy_event table (adapter-written).
     * Ключи: totalDeploys, deploysPerDay, displayValue, displayUnit, deployFreqRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildDeployFrequencyData(List<Long> projectIds,
                                                        int days) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<Long> serviceIds = resolveDoraServiceIds(resolvedIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        long totalDeploys = serviceIds.isEmpty()
            ? 0L
            : doraDeployEventRepository.countSuccessfulDeploys(workspaceId, serviceIds, dateFrom);
        double deploysPerDay = days > 0
            ? (double) totalDeploys / days
            : 0.0;

        DoraRating rating = DoraMetric.DEPLOYMENT_FREQUENCY.computeRating(
            totalDeploys == 0
                ? null
                : deploysPerDay);

        DataQualityState dqState;
        if (serviceIds.isEmpty()) {
            dqState = DataQualityState.MAPPING_REQUIRED;
        } else if (totalDeploys == 0) {
            dqState = DataQualityState.NO_EVENTS;
        } else {
            dqState = DataQualityState.CONFIGURED_OK;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDeploys", totalDeploys);
        result.put("deploysPerDay", round(deploysPerDay));
        result.put("displayValue", formatDeployFrequency(deploysPerDay));
        result.put("displayUnit", formatDeployFrequencyUnit(deploysPerDay));
        result.put("deployFreqRating", rating);
        result.put("dataQualityState", dqState);
        result.put("chartJson", buildDeployFrequencyChartJson(workspaceId, serviceIds, dateFrom));
        return result;
    }

    /**
     * Change Failure Rate: incidents / deployments * 100 over the given period.
     * Reads from the universal dora_deploy_event and dora_incident_event tables.
     * Ключи: totalIncidents, totalDeploys, cfrPercent, cfrRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildChangeFailureRateData(List<Long> projectIds,
                                                          int days) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<Long> serviceIds = resolveDoraServiceIds(resolvedIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        long totalIncidents = serviceIds.isEmpty()
            ? 0L
            : doraIncidentEventRepository.countIncidents(workspaceId, serviceIds, dateFrom);
        long totalDeploys = serviceIds.isEmpty()
            ? 0L
            : doraDeployEventRepository.countSuccessfulDeploys(workspaceId, serviceIds, dateFrom);

        Double cfrPercent = totalDeploys > 0
            ? round(totalIncidents * 100.0 / totalDeploys)
            : null;

        DoraRating rating = DoraMetric.CHANGE_FAILURE_RATE.computeRating(cfrPercent);

        DataQualityState dqState;
        if (serviceIds.isEmpty()) {
            dqState = DataQualityState.MAPPING_REQUIRED;
        } else if (totalDeploys == 0) {
            dqState = DataQualityState.NO_EVENTS;
        } else if (totalIncidents == 0) {
            dqState = DataQualityState.PARTIAL_DATA;
        } else {
            dqState = DataQualityState.CONFIGURED_OK;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalIncidents", totalIncidents);
        result.put("totalDeploys", totalDeploys);
        result.put("cfrPercent", cfrPercent);
        result.put("cfrRating", rating);
        result.put("dataQualityState", dqState);
        result.put("chartJson", buildCfrChartJson(workspaceId, serviceIds, dateFrom));
        return result;
    }

    private String buildCfrChartJson(Long workspaceId,
                                     List<Long> serviceIds,
                                     Instant dateFrom) {
        List<DeployFrequencyWeekProjection> deployWeeks = serviceIds.isEmpty()
            ? List.of()
            : doraDeployEventRepository.countDeploysByWeek(workspaceId, serviceIds, dateFrom);
        List<IncidentWeekProjection> incidentWeeks = serviceIds.isEmpty()
            ? List.of()
            : doraIncidentEventRepository.countIncidentsByWeek(workspaceId, serviceIds, dateFrom);

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
     * Reads from the universal dora_incident_event table (adapter-written).
     * Ключи: mttrHours, totalIncidents, mttrRating, chartJson.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildMttrData(List<Long> projectIds,
                                             int days) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<Long> serviceIds = resolveDoraServiceIds(resolvedIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        Double avgHours = serviceIds.isEmpty()
            ? null
            : doraIncidentEventRepository.findAvgMttrHours(workspaceId, serviceIds, dateFrom);
        long totalIncidents = serviceIds.isEmpty()
            ? 0L
            : doraIncidentEventRepository.countResolvedIncidents(workspaceId, serviceIds, dateFrom);

        Double mttrHours = avgHours != null
            ? round(avgHours)
            : null;
        DoraRating rating = DoraMetric.MTTR.computeRating(mttrHours);

        DataQualityState dqState;
        if (serviceIds.isEmpty()) {
            dqState = DataQualityState.MAPPING_REQUIRED;
        } else if (totalIncidents == 0) {
            dqState = DataQualityState.NO_EVENTS;
        } else {
            dqState = DataQualityState.CONFIGURED_OK;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mttrHours", mttrHours);
        result.put("totalIncidents", totalIncidents);
        result.put("mttrRating", rating);
        result.put("dataQualityState", dqState);
        result.put("chartJson", buildMttrChartJson(workspaceId, serviceIds, dateFrom));
        return result;
    }

    private String buildMttrChartJson(Long workspaceId,
                                      List<Long> serviceIds,
                                      Instant dateFrom) {
        List<MttrIncidentProjection> incidents = serviceIds.isEmpty()
            ? List.of()
            : doraIncidentEventRepository.findMttrIncidents(workspaceId, serviceIds, dateFrom);

        List<String> labels = new ArrayList<>();
        List<String> jiraKeys = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (MttrIncidentProjection row : incidents) {
            labels.add(row.getWeekLabel());
            jiraKeys.add(row.getJiraKey());
            values.add(round(row.getDurationHours()));
        }

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "jiraKeys", jiraKeys,
            "values", values
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

    private String buildDeployFrequencyChartJson(Long workspaceId,
                                                 List<Long> serviceIds,
                                                 Instant dateFrom) {
        List<DeployFrequencyWeekProjection> weekly = serviceIds.isEmpty()
            ? List.of()
            : doraDeployEventRepository.countDeploysByWeek(workspaceId, serviceIds, dateFrom);

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

    /**
     * Resolves DORA service IDs for the given tracked project IDs by looking up GITLAB source mappings.
     * Returns an empty list if no mappings exist yet (e.g. sync hasn't run after migration).
     */
    private List<Long> resolveDoraServiceIds(List<Long> trackedProjectIds) {
        if (trackedProjectIds.isEmpty()) {
            return List.of();
        }
        List<String> sourceKeys = trackedProjectIds.stream()
            .map(String::valueOf)
            .toList();
        return doraServiceMappingRepository
            .findAllBySourceTypeAndSourceKeyIn("GITLAB", sourceKeys)
            .stream()
            .map(DoraServiceMapping::getDoraServiceId)
            .distinct()
            .toList();
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
     * Reads from the universal dora_deploy_event table.
     * Returns null if no deploys found.
     */
    @Transactional(readOnly = true)
    public Double computeDeploysPerDay(List<Long> projectIds,
                                       Instant dateFrom,
                                       Instant dateTo,
                                       int days) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<Long> serviceIds = resolveDoraServiceIds(resolvedIds);
        if (serviceIds.isEmpty()) {
            return null;
        }
        long total = doraDeployEventRepository
            .countSuccessfulDeploysBetween(workspaceId, serviceIds, dateFrom, dateTo);
        if (total == 0) {
            return null;
        }
        return round((double) total / days);
    }

    /**
     * Returns average MTTR (hours) for resolved incidents in the period starting at dateFrom.
     * Reads from the universal dora_incident_event table.
     * Returns null if no data available.
     */
    @Transactional(readOnly = true)
    public Double computeMttrHours(List<Long> projectIds,
                                    Instant dateFrom) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<Long> serviceIds = resolveDoraServiceIds(resolvedIds);
        if (serviceIds.isEmpty()) {
            return null;
        }
        Double avgHours = doraIncidentEventRepository.findAvgMttrHours(workspaceId, serviceIds, dateFrom);
        if (avgHours == null) {
            return null;
        }
        return round(avgHours);
    }

    /**
     * Builds per-project health rows for the "Сервисы команды" table.
     * Health score (0-100) is a weighted composite of Lead Time, Deploy Freq, CFR, and activity.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "PMD.CognitiveComplexity"})
    public List<ServiceHealthRow> buildServiceHealthData(List<Long> projectIds,
                                                         int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<TrackedProject> projects = trackedProjectRepository.findAllById(resolvedIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);
        Instant prevFrom = dateFrom.minus(days, ChronoUnit.DAYS);

        List<ServiceHealthRow> rows = new ArrayList<>();
        for (TrackedProject project : projects) {
            List<Long> pid = List.of(project.getId());
            rows.add(buildSingleProjectHealth(project, pid, days, dateFrom, prevFrom));
        }
        rows.sort(Comparator.comparingInt(ServiceHealthRow::healthScore).reversed());
        return rows;
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private ServiceHealthRow buildSingleProjectHealth(TrackedProject project,
                                                       List<Long> pid,
                                                       int days,
                                                       Instant dateFrom,
                                                       Instant prevFrom) {
        Long workspaceId = WorkspaceContext.get();
        List<Long> serviceIds = resolveDoraServiceIds(pid);

        // MR count + Lead Time (stays on MR table — prod_deployed_at is in release_tag)
        List<RealLeadTimeSummaryProjection> ltRows =
            mergeRequestRepository.findRealLeadTimeSummary(pid, dateFrom);
        int mrCount = ltRows.isEmpty() || ltRows.getFirst().getMrCount() == null
            ? 0
            : ltRows.getFirst().getMrCount().intValue();

        Double medianDays = ltRows.isEmpty() || ltRows.getFirst().getMedianDays() == null
            ? null
            : round(ltRows.getFirst().getMedianDays());
        DoraRating ltRating = DoraMetric.LEAD_TIME_FOR_CHANGES.computeRating(medianDays);

        // Deploy Frequency — from universal dora_deploy_event table
        long totalDeploys = serviceIds.isEmpty()
            ? 0L
            : doraDeployEventRepository.countSuccessfulDeploys(workspaceId, serviceIds, dateFrom);
        double deploysPerDay = days > 0 ? (double) totalDeploys / days : 0;
        double deploysPerWeek = round(deploysPerDay * 7);
        DoraRating dfRating = DoraMetric.DEPLOYMENT_FREQUENCY.computeRating(
            totalDeploys == 0 ? null : deploysPerDay);

        // CFR — from universal dora_incident_event table
        long incidents = serviceIds.isEmpty()
            ? 0L
            : doraIncidentEventRepository.countIncidents(workspaceId, serviceIds, dateFrom);
        Double cfrPercent = totalDeploys > 0
            ? round(incidents * 100.0 / totalDeploys)
            : null;
        DoraRating cfrRating = DoraMetric.CHANGE_FAILURE_RATE.computeRating(cfrPercent);

        // Health score
        int healthScore = computeHealthScore(ltRating, dfRating, cfrRating, mrCount, days);

        // Trend: compare with previous period
        String trend = computeTrend(pid, serviceIds, days, healthScore, dateFrom, prevFrom);

        // Trend sparkline: weekly health scores over the period
        List<Integer> trendData = computeWeeklyTrend(pid, serviceIds, days);

        return new ServiceHealthRow(
            project.getId(),
            project.getName(),
            mrCount,
            healthScore,
            deploysPerWeek,
            medianDays,
            cfrPercent,
            incidents,
            trend,
            trendData
        );
    }

    private static int computeHealthScore(DoraRating ltRating,
                                           DoraRating dfRating,
                                           DoraRating cfrRating,
                                           int mrCount,
                                           int days) {
        // Each DORA rating → 0-100 sub-score
        int ltScore = ratingToScore(ltRating);
        int dfScore = ratingToScore(dfRating);
        int cfrScore = ratingToScore(cfrRating);

        // Activity: MR/week → score
        double mrsPerWeek = days > 0 ? mrCount * 7.0 / days : 0;
        int activityScore;
        if (mrsPerWeek >= 10) {
            activityScore = 100;
        } else if (mrsPerWeek >= 5) {
            activityScore = 80;
        } else if (mrsPerWeek >= 2) {
            activityScore = 55;
        } else if (mrsPerWeek >= 1) {
            activityScore = 35;
        } else {
            activityScore = 15;
        }

        // Weighted average: LT 30%, DF 25%, CFR 25%, Activity 20%
        double raw = ltScore * 0.30 + dfScore * 0.25 + cfrScore * 0.25 + activityScore * 0.20;
        return (int) Math.round(raw);
    }

    private static int ratingToScore(DoraRating rating) {
        return switch (rating) {
            case ELITE -> 100;
            case HIGH -> 80;
            case MEDIUM -> 55;
            case LOW -> 25;
            case NO_DATA -> 50;
        };
    }

    private String computeTrend(List<Long> pid,
                                 List<Long> serviceIds,
                                 int days,
                                 int currentScore,
                                 Instant dateFrom,
                                 Instant prevFrom) {
        int prevScore = computePeriodHealthScore(pid, serviceIds, days, prevFrom, dateFrom);
        int diff = currentScore - prevScore;
        if (diff > 3) {
            return "up";
        }
        if (diff < -3) {
            return "down";
        }
        return "flat";
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private int computePeriodHealthScore(List<Long> pid,
                                          List<Long> serviceIds,
                                          int days,
                                          Instant from,
                                          Instant to) {
        Long workspaceId = WorkspaceContext.get();

        List<RealLeadTimeSummaryProjection> ltRows =
            mergeRequestRepository.findRealLeadTimeSummaryBetween(pid, from, to);
        Double median = ltRows.isEmpty() ? null : ltRows.getFirst().getMedianDays();
        DoraRating ltR = DoraMetric.LEAD_TIME_FOR_CHANGES.computeRating(
            median != null ? round(median) : null);

        long deploys = serviceIds.isEmpty()
            ? 0L
            : doraDeployEventRepository.countSuccessfulDeploysBetween(workspaceId, serviceIds, from, to);
        double dpd = days > 0 ? (double) deploys / days : 0;
        DoraRating dfR = DoraMetric.DEPLOYMENT_FREQUENCY.computeRating(
            deploys == 0 ? null : dpd);

        long inc = serviceIds.isEmpty()
            ? 0L
            : doraIncidentEventRepository.countIncidentsBetween(workspaceId, serviceIds, from, to);
        Double cfr = deploys > 0 ? round(inc * 100.0 / deploys) : null;
        DoraRating cfrR = DoraMetric.CHANGE_FAILURE_RATE.computeRating(cfr);

        int mrs = ltRows.isEmpty() || ltRows.getFirst().getMrCount() == null
            ? 0 : ltRows.getFirst().getMrCount().intValue();
        return computeHealthScore(ltR, dfR, cfrR, mrs, days);
    }

    private List<Integer> computeWeeklyTrend(List<Long> pid, List<Long> serviceIds, int days) {
        Instant now = DateTimeUtils.now();
        int weekCount = Math.max(days / 7, 2);
        int windowDays = 7;
        List<Integer> scores = new ArrayList<>();
        for (int i = weekCount - 1; i >= 0; i--) {
            Instant to = now.minus((long) i * 7, ChronoUnit.DAYS);
            Instant from = to.minus(windowDays, ChronoUnit.DAYS);
            scores.add(computePeriodHealthScore(pid, serviceIds, windowDays, from, to));
        }
        return scores;
    }

    public record ServiceHealthRow(
        Long projectId,
        String projectName,
        int mrCount,
        int healthScore,
        double deploysPerWeek,
        Double leadTimeDays,
        Double cfrPercent,
        long incidents,
        String trend,
        List<Integer> trendData
    ) {

        /**
         * SVG stroke-dasharray offset for the health ring (locale-safe, always uses dot).
         */
        public String dashOffset() {
            return String.format(java.util.Locale.US, "%.1f", healthScore * 1.068);
        }
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
