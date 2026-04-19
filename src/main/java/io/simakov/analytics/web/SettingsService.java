package io.simakov.analytics.web;

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
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.snapshot.SnapshotService;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.CreatedProjectResult;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final int BACKFILL_DAYS = 360;

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;
    private final TrackedProjectMapper trackedProjectMapper;
    private final TrackedUserMapper trackedUserMapper;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final ContributorDiscoveryService contributorDiscoveryService;
    private final UserAliasService userAliasService;
    private final SnapshotService snapshotService;

    // ── GitLab Sources ───────────────────────────────────────────────────────

    public GitSource createSource(CreateGitSourceRequest request) {
        GitSource source = GitSource.builder()
            .workspaceId(WorkspaceContext.get())
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .build();
        return gitSourceRepository.save(source);
    }

    public void deleteSource(Long id) {
        Long workspaceId = WorkspaceContext.get();
        gitSourceRepository.findById(id)
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        gitSourceRepository.deleteById(id);
    }

    // ── Tracked Projects ─────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:IllegalCatch")
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

    public void deleteProject(Long id) {
        Long workspaceId = WorkspaceContext.get();
        trackedProjectRepository.findById(id)
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject", id));
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
        snapshotService.runDailyBackfillAsync(workspaceId, BACKFILL_DAYS);
        return saved;
    }

    public TrackedUser createUser(CreateTrackedUserRequest request) {
        Long workspaceId = WorkspaceContext.get();
        TrackedUser entity = trackedUserMapper.toEntity(request);
        entity.setWorkspaceId(workspaceId);
        TrackedUser saved = trackedUserRepository.save(entity);
        userAliasService.saveAlias(saved.getId(), request.email());
        userAliasService.saveAliases(saved.getId(), request.aliasEmails());
        snapshotService.runDailyBackfillAsync(workspaceId, BACKFILL_DAYS);
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

    public void deleteUser(Long id) {
        Long workspaceId = WorkspaceContext.get();
        trackedUserRepository.findById(id)
            .filter(u -> workspaceId.equals(u.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedUser", id));
        trackedUserRepository.deleteById(id);
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    public void triggerSnapshotBackfill() {
        snapshotService.runDailyBackfillAsync(WorkspaceContext.get(), BACKFILL_DAYS);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    public SyncJobResponse getSyncStatus(Long jobId) {
        Long workspaceId = WorkspaceContext.get();
        SyncJob job = syncJobService.findById(jobId);
        if (!workspaceId.equals(job.getWorkspaceId())) {
            throw new ResourceNotFoundException("SyncJob", jobId);
        }
        return SyncJobResponse.from(job);
    }

    public SyncJobResponse retrySync(Long jobId) {
        Long workspaceId = WorkspaceContext.get();
        SyncJob job = syncJobService.findById(jobId);
        if (!workspaceId.equals(job.getWorkspaceId())) {
            throw new ResourceNotFoundException("SyncJob", jobId);
        }
        ManualSyncRequest request = syncJobService.getPayload(jobId);
        SyncJob newJob = syncJobService.create(workspaceId, request);
        syncOrchestrator.orchestrateAsync(newJob.getId(), request);
        return SyncJobResponse.from(newJob);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private SyncJobResponse triggerBackfill(Long trackedProjectId) {
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = dateTo.minus(BACKFILL_DAYS, ChronoUnit.DAYS);
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(trackedProjectId), dateFrom, dateTo, true, true, true
        );
        SyncJob job = syncJobService.create(WorkspaceContext.get(), request);
        syncOrchestrator.orchestrateAsync(job.getId(), request);
        return SyncJobResponse.from(job);
    }
}
