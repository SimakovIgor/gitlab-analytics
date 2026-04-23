package io.simakov.analytics.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.CreateGitSourceRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.api.mapper.TrackedProjectMapper;
import io.simakov.analytics.api.mapper.TrackedUserMapper;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MrAuthorWithCountProjection;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.UserMrCountProjection;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.snapshot.SnapshotService;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.util.BotDetector;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.CreatedProjectResult;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final int BACKFILL_DAYS = 360;

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final ReleaseTagRepository releaseTagRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;
    private final TrackedProjectMapper trackedProjectMapper;
    private final TrackedUserMapper trackedUserMapper;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final ObjectMapper objectMapper;
    private final ContributorDiscoveryService contributorDiscoveryService;
    private final UserAliasService userAliasService;
    private final SnapshotService snapshotService;
    private final TrackedUserAliasRepository aliasRepository;

    // ── GitLab Sources ───────────────────────────────────────────────────────

    public GitSource createSource(CreateGitSourceRequest request) {
        GitSource source = GitSource.builder()
            .workspaceId(WorkspaceContext.get())
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .build();
        return gitSourceRepository.save(source);
    }

    @Transactional
    public void deleteSource(Long id) {
        Long workspaceId = WorkspaceContext.get();
        gitSourceRepository.findById(id)
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        gitSourceRepository.deleteById(id);
    }

    // ── Tracked Projects ─────────────────────────────────────────────────────

    public Map<String, Object> validateToken(Long sourceId,
                                             String token) {
        Long workspaceId = WorkspaceContext.get();
        GitSource source = gitSourceRepository.findById(sourceId)
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        try {
            var user = gitLabApiClient.getCurrentUser(source.getBaseUrl(), token);
            return Map.of("valid", true, "username", user.username() != null
                ? user.username()
                : "");
        } catch (Exception e) {
            return Map.of("valid", false, "error", "Токен недействителен или недостаточно прав");
        }
    }

    public List<GitLabProjectDto> searchProjects(Long sourceId,
                                                 String q,
                                                 String token) {
        Long workspaceId = WorkspaceContext.get();
        GitSource source = gitSourceRepository.findById(sourceId)
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        return gitLabApiClient.searchProjects(source.getBaseUrl(), token, q);
    }

    public CreatedProjectResult createProject(CreateTrackedProjectRequest request) {
        Long workspaceId = WorkspaceContext.get();
        gitSourceRepository.findById(request.gitSourceId())
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", request.gitSourceId()));
        TrackedProject project = trackedProjectMapper.toEntity(request);
        project.setWorkspaceId(workspaceId);
        project.setTokenEncrypted(encryptionService.encrypt(request.token()));
        TrackedProject saved = trackedProjectRepository.save(project);
        SyncJobResponse job = triggerBackfill(saved.getId());
        return new CreatedProjectResult(saved, job.jobId());
    }

    @Transactional
    public void deleteProject(Long id) {
        Long workspaceId = WorkspaceContext.get();
        trackedProjectRepository.findById(id)
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject", id));
        mergeRequestRepository.clearReleaseTagId(id);
        releaseTagRepository.deleteAll(
            releaseTagRepository.findAllByTrackedProjectIdOrderByTagCreatedAtDesc(id));
        trackedProjectRepository.deleteById(id);
    }

    public SyncJobResponse backfillProject(Long id) {
        Long workspaceId = WorkspaceContext.get();
        trackedProjectRepository.findById(id)
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject", id));
        return triggerBackfill(id);
    }

    // ── Users ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GitLabUserSearchDto> searchUsers(Long sourceId,
                                                 String q) {
        Long workspaceId = WorkspaceContext.get();
        GitSource source = gitSourceRepository.findById(sourceId)
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        TrackedProject project = trackedProjectRepository.findFirstByWorkspaceIdAndGitSourceId(workspaceId, sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject for GitSource", sourceId));
        String token = encryptionService.decrypt(project.getTokenEncrypted());
        return gitLabApiClient.searchUsers(source.getBaseUrl(), token, q);
    }

    public List<DiscoveredContributor> discoverContributors() {
        return contributorDiscoveryService.discover();
    }

    public List<Map<String, Object>> getTrackedUsersWithMrCount() {
        Long workspaceId = WorkspaceContext.get();
        Map<Long, Long> mrCountById = mergeRequestRepository.countMrsByTrackedUser(workspaceId)
            .stream()
            .collect(Collectors.toMap(UserMrCountProjection::getTrackedUserId, UserMrCountProjection::getMrCount));

        List<Map<String, Object>> result = new ArrayList<>(
            trackedUserRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("displayName", u.getDisplayName());
                    m.put("mrCount", mrCountById.getOrDefault(u.getId(), 0L));
                    m.put("suspectedBot", BotDetector.isSuspectedBot(u.getDisplayName(), null));
                    return m;
                })
                .toList()
        );

        // Append MR authors that were skipped during auto-discovery (bots/placeholders)
        List<Long> trackedUserIds = result.stream()
            .map(m -> ((Number) m.get("id")).longValue())
            .filter(id -> id > 0)
            .toList();
        Set<Long> trackedGitlabIds = aliasRepository.findByTrackedUserIdIn(trackedUserIds).stream()
            .filter(a -> a.getGitlabUserId() != null)
            .map(io.simakov.analytics.domain.model.TrackedUserAlias::getGitlabUserId)
            .collect(Collectors.toSet());
        for (MrAuthorWithCountProjection author : mergeRequestRepository.findDistinctAuthorsByWorkspace(workspaceId)) {
            if (trackedGitlabIds.contains(author.getAuthorGitlabUserId())) {
                continue;
            }
            String name = author.getAuthorName() != null ? author.getAuthorName() : author.getAuthorUsername();
            Map<String, Object> m = new HashMap<>();
            m.put("id", -author.getAuthorGitlabUserId());
            m.put("displayName", name);
            m.put("mrCount", author.getMrCount());
            m.put("suspectedBot", BotDetector.isSuspectedBot(name, author.getAuthorUsername()));
            m.put("gitlabUserId", author.getAuthorGitlabUserId());
            m.put("username", author.getAuthorUsername());
            result.add(m);
        }

        result.sort(Comparator
            .comparing((Map<String, Object> m) -> Boolean.TRUE.equals(m.get("suspectedBot")))
            .thenComparing(m -> -((Number) m.get("mrCount")).longValue()));
        return result;
    }

    @Transactional
    public List<TrackedUser> createUsersBulk(List<CreateTrackedUserRequest> requests) {
        Long workspaceId = WorkspaceContext.get();
        List<TrackedUser> saved = requests.stream()
            .map(req -> {
                TrackedUser entity = trackedUserMapper.toEntity(req);
                entity.setWorkspaceId(workspaceId);
                TrackedUser user = trackedUserRepository.save(entity);
                userAliasService.saveAlias(user.getId(), req.email());
                userAliasService.saveAliases(user.getId(), req.aliasEmails());
                return user;
            })
            .toList();
        scheduleBackfill(workspaceId);
        return saved;
    }

    public TrackedUser createUser(CreateTrackedUserRequest request) {
        Long workspaceId = WorkspaceContext.get();
        TrackedUser entity = trackedUserMapper.toEntity(request);
        entity.setWorkspaceId(workspaceId);
        TrackedUser saved = trackedUserRepository.save(entity);
        userAliasService.saveAlias(saved.getId(), request.email());
        userAliasService.saveAliases(saved.getId(), request.aliasEmails());
        scheduleBackfill(workspaceId);
        return saved;
    }

    public void linkGitlabAccount(Long userId,
                                  Long gitlabUserId,
                                  String username) {
        Long workspaceId = WorkspaceContext.get();
        trackedUserRepository.findById(userId)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", userId));
        userAliasService.linkGitlabAccount(userId, gitlabUserId, username);
    }

    @Transactional
    public void deleteUser(Long id) {
        Long workspaceId = WorkspaceContext.get();
        trackedUserRepository.findById(id)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));
        trackedUserRepository.deleteById(id);
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    /**
     * Synchronous: blocks until all snapshots are created, returns count. Used by onboarding.
     */
    public int triggerSnapshotBackfill() {
        return snapshotService.runDailyBackfill(WorkspaceContext.get(), BACKFILL_DAYS);
    }

    /**
     * Async: submits backfill to snapshotExecutor and returns immediately. Used by settings page.
     */
    public void scheduleSnapshotBackfill() {
        scheduleBackfill(WorkspaceContext.get());
    }

    private void scheduleBackfill(Long workspaceId) {
        try {
            snapshotService.runDailyBackfillAsync(workspaceId, BACKFILL_DAYS);
        } catch (Exception e) {
            // snapshotExecutor uses DiscardPolicy, but guard defensively: a rejected backfill must
            // never propagate into @Transactional callers and trigger an unexpected rollback.
            log.warn("Snapshot backfill could not be scheduled for workspace={}: {}", workspaceId, e.getMessage());
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    public SyncJobResponse getSyncStatus(Long jobId) {
        Long workspaceId = WorkspaceContext.get();
        SyncJob job = syncJobService.findById(jobId);
        if (!workspaceId.equals(job.getWorkspaceId())) {
            throw new ResourceNotFoundException("SyncJob", jobId);
        }
        return SyncJobResponse.from(job, resolveProjectName(job));
    }

    private String resolveProjectName(SyncJob job) {
        String payload = job.getPayloadJson();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(payload, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            List<Integer> ids = (List<Integer>) map.get("projectIds");
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            List<Long> projectIds = ids.stream().map(Integer::longValue).toList();
            List<TrackedProject> projects = trackedProjectRepository.findAllById(projectIds);
            if (projects.isEmpty()) {
                return null;
            }
            return projects.stream()
                .map(TrackedProject::getName)
                .collect(Collectors.joining(", "));
        } catch (Exception e) {
            log.warn("Failed to resolve project name for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    public SyncJobResponse retrySync(Long jobId) {
        Long workspaceId = WorkspaceContext.get();
        SyncJob job = syncJobService.findById(jobId);
        if (!workspaceId.equals(job.getWorkspaceId())) {
            throw new ResourceNotFoundException("SyncJob", jobId);
        }
        SyncJobPhase phase = job.getPhase() != null
            ? job.getPhase()
            : SyncJobPhase.ENRICH;
        ManualSyncRequest request = syncJobService.getPayload(jobId);
        if (phase == SyncJobPhase.RELEASE) {
            SyncJob newJob = syncJobService.createReleaseJob(workspaceId, request);
            syncOrchestrator.orchestrateReleaseAsync(newJob.getId());
            return SyncJobResponse.from(newJob);
        }
        SyncJob newJob = syncJobService.create(workspaceId, request, phase);
        syncOrchestrator.orchestrateAsync(newJob.getId(), request, phase);
        return SyncJobResponse.from(newJob);
    }

    /**
     * Creates per-project RELEASE jobs for the given project IDs (or all enabled projects
     * if the list is empty). Skips projects that already have an active RELEASE job.
     *
     * @return IDs of the newly created RELEASE jobs
     */
    public List<Long> startReleaseSyncForProjects(List<Long> projectIds) {
        Long workspaceId = WorkspaceContext.get();
        List<TrackedProject> targets;
        if (projectIds.isEmpty()) {
            targets = trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId);
        } else {
            targets = trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId)
                .stream()
                .filter(p -> projectIds.contains(p.getId()))
                .toList();
        }
        List<Long> jobIds = new ArrayList<>();
        Instant now = DateTimeUtils.now();
        for (TrackedProject project : targets) {
            List<Long> ids = List.of(project.getId());
            if (syncJobService.findActiveReleaseJobForProjects(workspaceId, ids).isPresent()) {
                continue;
            }
            ManualSyncRequest request = new ManualSyncRequest(
                ids, now.minus(BACKFILL_DAYS, ChronoUnit.DAYS), now,
                false, false, false, false
            );
            SyncJob job = syncJobService.createReleaseJob(workspaceId, request);
            syncOrchestrator.orchestrateReleaseAsync(job.getId());
            jobIds.add(job.getId());
        }
        return jobIds;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private SyncJobResponse triggerBackfill(Long trackedProjectId) {
        Long workspaceId = WorkspaceContext.get();
        // Idempotency: return the running job instead of starting a duplicate
        var activeJob = syncJobService.findActiveJobForProjects(workspaceId, List.of(trackedProjectId));
        if (activeJob.isPresent()) {
            return SyncJobResponse.from(activeJob.get());
        }
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = dateTo.minus(BACKFILL_DAYS, ChronoUnit.DAYS);
        // Phase 1: MR list only — fast (~2-5 min). Phase 2 (ENRICH) starts automatically after.
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(trackedProjectId), dateFrom, dateTo, false, false, false, false
        );
        SyncJob job = syncJobService.create(workspaceId, request, SyncJobPhase.FAST);
        syncOrchestrator.orchestrateAsync(job.getId(), request, SyncJobPhase.FAST);
        return SyncJobResponse.from(job);
    }
}
