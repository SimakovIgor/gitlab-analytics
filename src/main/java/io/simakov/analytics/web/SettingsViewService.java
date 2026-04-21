package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.web.dto.SyncHistoryPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettingsViewService {

    private static final DateTimeFormatter JOB_TIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final SyncJobRepository syncJobRepository;

    private static String formatDuration(Instant start,
                                         Instant end) {
        if (end == null) {
            return "в процессе";
        }
        long secs = ChronoUnit.SECONDS.between(start, end);
        if (secs < 60) {
            return secs + " с";
        }
        return (secs / 60) + " м " + (secs % 60) + " с";
    }

    private static String formatDurSecs(long secs) {
        if (secs <= 0) {
            return "—";
        }
        if (secs < 60) {
            return secs + " с";
        }
        return (secs / 60) + " м " + (secs % 60) + " с";
    }

    private static String toUiStatus(SyncJob job) {
        if (job.getStatus() == SyncStatus.STARTED) {
            return "running";
        }
        if (job.getStatus() == SyncStatus.FAILED) {
            return "failed";
        }
        return job.getPhase() == SyncJobPhase.FAST ? "partial" : "ok";
    }

    private static long durSecs(SyncJob job) {
        if (job.getFinishedAt() == null) {
            return 0L;
        }
        return ChronoUnit.SECONDS.between(job.getStartedAt(), job.getFinishedAt());
    }

    @Transactional(readOnly = true)
    public SettingsPageData buildSettingsPage() {
        Long workspaceId = WorkspaceContext.get();
        List<GitSource> sources = gitSourceRepository.findAllByWorkspaceId(workspaceId);
        List<TrackedProject> projects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);
        List<TrackedUser> users = trackedUserRepository.findAllByWorkspaceId(workspaceId);

        Map<Long, String> sourceNames = new HashMap<>();
        for (GitSource s : sources) {
            sourceNames.put(s.getId(), s.getName());
        }

        List<Long> userIds = users.stream().map(TrackedUser::getId).toList();
        Map<Long, List<TrackedUserAlias>> aliasesByUserId = aliasRepository.findByTrackedUserIdIn(userIds)
            .stream().collect(Collectors.groupingBy(TrackedUserAlias::getTrackedUserId));

        List<Map<String, Object>> usersWithAliases = users.stream()
            .map(u -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("user", u);
                entry.put("aliases", aliasesByUserId.getOrDefault(u.getId(), List.of()));
                return entry;
            })
            .toList();

        boolean hasSources = !sources.isEmpty();
        boolean hasProjects = !projects.isEmpty();
        boolean hasUsers = !users.isEmpty();

        List<Long> activeJobIds = syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED)
            .stream().map(SyncJob::getId).toList();

        List<SyncJob> rawJobs = syncJobRepository.findTop30ByWorkspaceIdOrderByStartedAtDesc(workspaceId);

        long maxNonFailedId = rawJobs.stream()
            .filter(j -> j.getStatus() != SyncStatus.FAILED)
            .mapToLong(SyncJob::getId)
            .max()
            .orElse(-1L);

        List<Map<String, Object>> recentJobs = rawJobs.stream()
            .map(job -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", job.getId());
                row.put("status", job.getStatus().name());
                row.put("startedAt", JOB_TIME_FMT.format(job.getStartedAt()));
                row.put("finishedAt", job.getFinishedAt() != null
                    ? JOB_TIME_FMT.format(job.getFinishedAt())
                    : null);
                row.put("duration", formatDuration(job.getStartedAt(), job.getFinishedAt()));
                row.put("errorMessage", job.getErrorMessage());
                row.put("canRetry", job.getStatus() == SyncStatus.FAILED
                    && job.getId() > maxNonFailedId);
                return row;
            })
            .toList();

        return new SettingsPageData(
            sources, projects, sourceNames, usersWithAliases,
            hasSources, hasProjects, hasUsers,
            !hasProjects || !hasUsers,
            activeJobIds, recentJobs
        );
    }

    @Transactional(readOnly = true)
    public SyncHistoryPageData buildSyncHistoryPage() {
        Long workspaceId = WorkspaceContext.get();
        List<SyncJob> rawJobs = syncJobRepository.findTop30ByWorkspaceIdOrderByStartedAtDesc(workspaceId);
        List<TrackedProject> projects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);

        long maxNonFailedId = rawJobs.stream()
            .filter(j -> j.getStatus() != SyncStatus.FAILED)
            .mapToLong(SyncJob::getId)
            .max()
            .orElse(-1L);

        List<Map<String, Object>> jobs = rawJobs.stream()
            .map(job -> buildJobRow(job, maxNonFailedId))
            .toList();

        List<Map<String, Object>> chartBars = buildChartBars(rawJobs);
        Map<String, Object> kpi = buildKpi14d(rawJobs);

        List<Long> activeJobIds = rawJobs.stream()
            .filter(j -> j.getStatus() == SyncStatus.STARTED)
            .map(SyncJob::getId)
            .toList();

        Long enrichmentJobId = rawJobs.stream()
            .filter(j -> j.getStatus() == SyncStatus.STARTED && j.getPhase() == SyncJobPhase.ENRICH)
            .map(SyncJob::getId)
            .findFirst()
            .orElse(null);

        return new SyncHistoryPageData(
            jobs, chartBars,
            (long) kpi.get("total14d"), (long) kpi.get("ok14d"),
            (long) kpi.get("partial14d"), (long) kpi.get("failed14d"),
            (String) kpi.get("avgDurLabel14d"),
            projects, activeJobIds, enrichmentJobId
        );
    }

    private Map<String, Object> buildJobRow(SyncJob job, long maxNonFailedId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", job.getId());
        row.put("uiStatus", toUiStatus(job));
        row.put("phase", job.getPhase() != null ? job.getPhase().name() : "ENRICH");
        row.put("startedAt", JOB_TIME_FMT.format(job.getStartedAt()));
        long secs = durSecs(job);
        row.put("durationSecs", secs);
        row.put("durationLabel", formatDurSecs(secs));
        row.put("totalMrs", job.getTotalMrs());
        row.put("errorMessage", job.getErrorMessage());
        row.put("canRetry", job.getStatus() == SyncStatus.FAILED && job.getId() > maxNonFailedId);
        return row;
    }

    private List<Map<String, Object>> buildChartBars(List<SyncJob> rawJobs) {
        List<SyncJob> recent14 = new ArrayList<>(rawJobs.stream().limit(14).toList());
        long maxSecs = recent14.stream()
            .filter(j -> j.getFinishedAt() != null && j.getStatus() != SyncStatus.FAILED)
            .mapToLong(SettingsViewService::durSecs)
            .max()
            .orElse(300L);
        Collections.reverse(recent14);
        return recent14.stream()
            .map(job -> {
                Map<String, Object> bar = new HashMap<>();
                String uiStatus = toUiStatus(job);
                long secs = durSecs(job);
                int heightPct = "failed".equals(uiStatus) || secs == 0
                    ? 8
                    : (int) Math.min(100L, Math.max(6L, secs * 100L / maxSecs));
                bar.put("uiStatus", uiStatus);
                bar.put("heightPct", heightPct);
                bar.put("dateLabel", JOB_TIME_FMT.format(job.getStartedAt()).substring(0, 5));
                bar.put("tooltip", JOB_TIME_FMT.format(job.getStartedAt())
                    + " · " + formatDurSecs(secs));
                return bar;
            })
            .toList();
    }

    private Map<String, Object> buildKpi14d(List<SyncJob> rawJobs) {
        Instant cutoff = Instant.now().minus(14, ChronoUnit.DAYS);
        List<SyncJob> jobs14d = rawJobs.stream()
            .filter(j -> j.getStartedAt().isAfter(cutoff))
            .toList();
        long ok = jobs14d.stream().filter(j -> "ok".equals(toUiStatus(j))).count();
        long partial = jobs14d.stream().filter(j -> "partial".equals(toUiStatus(j))).count();
        long failed = jobs14d.stream().filter(j -> "failed".equals(toUiStatus(j))).count();
        OptionalDouble avgDur = jobs14d.stream()
            .filter(j -> j.getFinishedAt() != null && !"failed".equals(toUiStatus(j)))
            .mapToLong(SettingsViewService::durSecs)
            .average();
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("total14d", (long) jobs14d.size());
        kpi.put("ok14d", ok);
        kpi.put("partial14d", partial);
        kpi.put("failed14d", failed);
        kpi.put("avgDurLabel14d", avgDur.isPresent() ? formatDurSecs((long) avgDur.getAsDouble()) : "—");
        return kpi;
    }
}
