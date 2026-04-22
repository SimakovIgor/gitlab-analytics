package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoraService {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final MergeRequestRepository mergeRequestRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final TrackedProjectRepository trackedProjectRepository;
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
     * Returns releases with MR count and real Lead Time (mr.created_at → prod_deployed_at).
     * Only includes releases that have been attributed to at least one MR, or have a prod deploy timestamp.
     */
    @Transactional(readOnly = true)
    public List<ReleaseRowDto> buildReleasesData(List<Long> projectIds) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        List<ReleaseTag> tags = releaseTagRepository.findAllByProjectIdsOrderByCreatedAtDesc(resolvedIds);

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
                DATE_FMT.format(tag.getTagCreatedAt()),
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
