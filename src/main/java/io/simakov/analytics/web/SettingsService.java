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
import io.simakov.analytics.snapshot.SnapshotService;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.CreatedProjectResult;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .build();
        return gitSourceRepository.save(source);
    }

    public void deleteSource(Long id) {
        if (!gitSourceRepository.existsById(id)) {
            throw new ResourceNotFoundException("GitSource", id);
        }
        gitSourceRepository.deleteById(id);
    }

    // ── Tracked Projects ─────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:IllegalCatch")
    public Map<String, Object> validateToken(Long sourceId, String token) {
        GitSource source = gitSourceRepository.findById(sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        try {
            var user = gitLabApiClient.getCurrentUser(source.getBaseUrl(), token);
            return Map.of("valid", true, "username", user.username() != null ? user.username() : "");
        } catch (Exception e) {
            return Map.of("valid", false, "error", "Токен недействителен или недостаточно прав");
        }
    }

    public List<GitLabProjectDto> searchProjects(Long sourceId,
                                                 String q,
                                                 String token) {
        GitSource source = gitSourceRepository.findById(sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        return gitLabApiClient.searchProjects(source.getBaseUrl(), token, q);
    }

    public CreatedProjectResult createProject(CreateTrackedProjectRequest request) {
        if (!gitSourceRepository.existsById(request.gitSourceId())) {
            throw new ResourceNotFoundException("GitSource", request.gitSourceId());
        }
        TrackedProject project = trackedProjectMapper.toEntity(request);
        project.setTokenEncrypted(encryptionService.encrypt(request.token()));
        TrackedProject saved = trackedProjectRepository.save(project);
        SyncJobResponse job = triggerBackfill(saved.getId());
        return new CreatedProjectResult(saved, job.jobId());
    }

    public void deleteProject(Long id) {
        if (!trackedProjectRepository.existsById(id)) {
            throw new ResourceNotFoundException("TrackedProject", id);
        }
        trackedProjectRepository.deleteById(id);
    }

    public SyncJobResponse backfillProject(Long id) {
        if (!trackedProjectRepository.existsById(id)) {
            throw new ResourceNotFoundException("TrackedProject", id);
        }
        return triggerBackfill(id);
    }

    // ── Users ────────────────────────────────────────────────────────────────

    public List<GitLabUserSearchDto> searchUsers(Long sourceId,
                                                 String q) {
        GitSource source = gitSourceRepository.findById(sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", sourceId));
        TrackedProject project = trackedProjectRepository.findFirstByGitSourceId(sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject for GitSource", sourceId));
        String token = encryptionService.decrypt(project.getTokenEncrypted());
        return gitLabApiClient.searchUsers(source.getBaseUrl(), token, q);
    }

    public List<DiscoveredContributor> discoverContributors() {
        return contributorDiscoveryService.discover();
    }

    public List<TrackedUser> createUsersBulk(List<CreateTrackedUserRequest> requests) {
        List<TrackedUser> saved = requests.stream()
            .map(req -> {
                TrackedUser user = trackedUserRepository.save(trackedUserMapper.toEntity(req));
                userAliasService.saveAlias(user.getId(), req.email());
                userAliasService.saveAliases(user.getId(), req.aliasEmails());
                return user;
            })
            .toList();
        snapshotService.runDailyBackfillAsync(BACKFILL_DAYS);
        return saved;
    }

    public TrackedUser createUser(CreateTrackedUserRequest request) {
        TrackedUser saved = trackedUserRepository.save(trackedUserMapper.toEntity(request));
        userAliasService.saveAlias(saved.getId(), request.email());
        userAliasService.saveAliases(saved.getId(), request.aliasEmails());
        snapshotService.runDailyBackfillAsync(BACKFILL_DAYS);
        return saved;
    }

    public void linkGitlabAccount(Long userId,
                                  Long gitlabUserId,
                                  String username) {
        if (!trackedUserRepository.existsById(userId)) {
            throw new ResourceNotFoundException("TrackedUser", userId);
        }
        userAliasService.linkGitlabAccount(userId, gitlabUserId, username);
    }

    public void deleteUser(Long id) {
        if (!trackedUserRepository.existsById(id)) {
            throw new ResourceNotFoundException("TrackedUser", id);
        }
        trackedUserRepository.deleteById(id);
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    public int triggerSnapshotBackfill() {
        return snapshotService.runDailyBackfill(BACKFILL_DAYS);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    public SyncJobResponse getSyncStatus(Long jobId) {
        return SyncJobResponse.from(syncJobService.findById(jobId));
    }

    public SyncJobResponse retrySync(Long jobId) {
        ManualSyncRequest request = syncJobService.getPayload(jobId);
        SyncJob newJob = syncJobService.create(request);
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
        SyncJob job = syncJobService.create(request);
        syncOrchestrator.orchestrateAsync(job.getId(), request);
        return SyncJobResponse.from(job);
    }
}
