package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.dto.SettingsPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
