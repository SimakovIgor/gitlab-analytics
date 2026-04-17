package io.simakov.analytics.web.controller;

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
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.ContributorDiscoveryService;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final int BACKFILL_DAYS = 360;
    private static final DateTimeFormatter JOB_TIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;
    private final TrackedProjectMapper trackedProjectMapper;
    private final TrackedUserMapper trackedUserMapper;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final SyncJobRepository syncJobRepository;
    private final ContributorDiscoveryService contributorDiscoveryService;

    @GetMapping
    public String settings(OAuth2AuthenticationToken authentication,
                           Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", resolveUser(authentication));
        }

        List<GitSource> sources = gitSourceRepository.findAll();
        List<TrackedProject> projects = trackedProjectRepository.findAll();
        List<TrackedUser> users = trackedUserRepository.findAll();

        Map<Long, String> sourceNames = new HashMap<>();
        for (GitSource s : sources) {
            sourceNames.put(s.getId(), s.getName());
        }

        List<Map<String, Object>> usersWithAliases = users.stream()
            .map(u -> {
                List<TrackedUserAlias> aliases = aliasRepository.findByTrackedUserId(u.getId());
                Map<String, Object> entry = new HashMap<>();
                entry.put("user", u);
                entry.put("aliases", aliases);
                return entry;
            })
            .toList();

        boolean hasSources = !sources.isEmpty();
        boolean hasProjects = !projects.isEmpty();
        boolean hasUsers = !users.isEmpty();

        model.addAttribute("sources", sources);
        model.addAttribute("projects", projects);
        model.addAttribute("sourceNames", sourceNames);
        model.addAttribute("usersWithAliases", usersWithAliases);
        List<Long> activeJobIds = syncJobRepository.findByStatusOrderByStartedAtDesc(SyncStatus.STARTED)
            .stream().map(SyncJob::getId).toList();

        List<SyncJob> rawJobs = syncJobRepository.findTop30ByOrderByStartedAtDesc();

        // Largest ID among non-failed jobs — any FAILED job with a smaller ID cannot retry.
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
                    ? JOB_TIME_FMT.format(job.getFinishedAt()) : null);
                row.put("duration", formatDuration(job.getStartedAt(), job.getFinishedAt()));
                row.put("errorMessage", job.getErrorMessage());
                row.put("canRetry", job.getStatus() == SyncStatus.FAILED
                    && job.getId() > maxNonFailedId);
                return row;
            })
            .toList();

        model.addAttribute("onboardingMode", !hasProjects || !hasUsers);
        model.addAttribute("hasSources", hasSources);
        model.addAttribute("hasProjects", hasProjects);
        model.addAttribute("hasUsers", hasUsers);
        model.addAttribute("activeJobIds", activeJobIds);
        model.addAttribute("recentJobs", recentJobs);
        return "settings";
    }

    // ── GitLab Sources ──────────────────────────────────────────────────────

    @PostMapping("/sources")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSource(@RequestBody @Valid CreateGitSourceRequest request) {
        GitSource source = GitSource.builder()
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .build();
        source = gitSourceRepository.save(source);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", source.getId(),
            "name", source.getName(),
            "baseUrl", source.getBaseUrl()
        ));
    }

    @DeleteMapping("/sources/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        if (!gitSourceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        gitSourceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sources/{id}/projects/search")
    @ResponseBody
    public List<GitLabProjectDto> searchProjects(@PathVariable Long id,
                                                 @RequestParam String q,
                                                 @RequestParam String token) {
        GitSource source = gitSourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        return gitLabApiClient.searchProjects(source.getBaseUrl(), token, q);
    }

    // ── Tracked Projects ────────────────────────────────────────────────────

    @PostMapping("/projects")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createProject(@RequestBody @Valid CreateTrackedProjectRequest request) {
        if (!gitSourceRepository.existsById(request.gitSourceId())) {
            throw new ResourceNotFoundException("GitSource", request.gitSourceId());
        }
        TrackedProject project = trackedProjectMapper.toEntity(request);
        project.setTokenEncrypted(encryptionService.encrypt(request.token()));
        TrackedProject saved = trackedProjectRepository.save(project);

        SyncJobResponse job = triggerBackfill(saved.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", saved.getId(),
            "name", saved.getName(),
            "pathWithNamespace", saved.getPathWithNamespace(),
            "enabled", saved.isEnabled(),
            "jobId", job.jobId()
        ));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        if (!trackedProjectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        trackedProjectRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{id}/backfill")
    @ResponseBody
    public SyncJobResponse backfillProject(@PathVariable Long id) {
        if (!trackedProjectRepository.existsById(id)) {
            throw new ResourceNotFoundException("TrackedProject", id);
        }
        return triggerBackfill(id);
    }

    @GetMapping("/sources/{id}/users/search")
    @ResponseBody
    public List<GitLabUserSearchDto> searchUsers(@PathVariable Long id,
                                                 @RequestParam String q) {
        GitSource source = gitSourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        TrackedProject project = trackedProjectRepository.findFirstByGitSourceId(id)
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject for GitSource", id));
        String token = encryptionService.decrypt(project.getTokenEncrypted());
        return gitLabApiClient.searchUsers(source.getBaseUrl(), token, q);
    }

    // ── Contributor discovery ────────────────────────────────────────────────

    @GetMapping("/users/discovered")
    @ResponseBody
    public List<DiscoveredContributor> discoverContributors() {
        return contributorDiscoveryService.discover();
    }

    @PostMapping("/users/bulk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUsersBulk(
            @RequestBody List<CreateTrackedUserRequest> requests) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (CreateTrackedUserRequest req : requests) {
            TrackedUser saved = trackedUserRepository.save(trackedUserMapper.toEntity(req));
            saveAliasEmail(saved.getId(), req.email());
            saveAliasEmails(saved.getId(), req.aliasEmails());
            created.add(Map.of(
                "id", saved.getId(),
                "displayName", saved.getDisplayName(),
                "email", saved.getEmail() != null ? saved.getEmail() : ""
            ));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("created", created));
    }

    private void saveAliasEmail(Long userId, String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalizedEmail = email.toLowerCase(Locale.ROOT).strip();
        if (!aliasRepository.existsByTrackedUserIdAndEmail(userId, normalizedEmail)) {
            List<Long> gitlabUserIds = mergeRequestRepository.findAuthorGitlabUserIdByCommitEmail(normalizedEmail);
            Long gitlabUserId = gitlabUserIds.isEmpty() ? null : gitlabUserIds.get(0);
            aliasRepository.save(TrackedUserAlias.builder()
                .trackedUserId(userId)
                .email(normalizedEmail)
                .gitlabUserId(gitlabUserId)
                .build());
        }
    }

    private void saveAliasEmails(Long userId, List<String> aliasEmails) {
        if (aliasEmails == null) {
            return;
        }
        for (String aliasEmail : aliasEmails) {
            if (aliasEmail == null || aliasEmail.isBlank()) {
                continue;
            }
            String normalizedEmail = aliasEmail.toLowerCase(Locale.ROOT).strip();
            List<Long> gitlabUserIds = mergeRequestRepository
                .findAuthorGitlabUserIdByCommitEmail(normalizedEmail);
            Long gitlabUserId = gitlabUserIds.isEmpty() ? null : gitlabUserIds.get(0);
            aliasRepository.save(TrackedUserAlias.builder()
                .trackedUserId(userId)
                .email(normalizedEmail)
                .gitlabUserId(gitlabUserId)
                .build());
        }
    }

    // ── Tracked Users ────────────────────────────────────────────────────────

    @PostMapping("/users")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody @Valid CreateTrackedUserRequest request) {
        TrackedUser saved = trackedUserRepository.save(trackedUserMapper.toEntity(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", saved.getId(),
            "displayName", saved.getDisplayName(),
            "email", saved.getEmail() != null ? saved.getEmail() : "",
            "enabled", saved.isEnabled()
        ));
    }

    @DeleteMapping("/users/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        if (!trackedUserRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        trackedUserRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Sync status polling ──────────────────────────────────────────────────

    @GetMapping("/sync/{jobId}")
    @ResponseBody
    public SyncJobResponse getSyncStatus(@PathVariable Long jobId) {
        return SyncJobResponse.from(syncJobService.findById(jobId));
    }

    @PostMapping("/sync/{jobId}/retry")
    @ResponseBody
    public SyncJobResponse retrySync(@PathVariable Long jobId) {
        ManualSyncRequest request = syncJobService.getPayload(jobId);
        SyncJob newJob = syncJobService.create(request);
        syncOrchestrator.orchestrateAsync(newJob.getId(), request);
        return SyncJobResponse.from(newJob);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private SyncJobResponse triggerBackfill(Long trackedProjectId) {
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = dateTo.minus(BACKFILL_DAYS, ChronoUnit.DAYS);
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(trackedProjectId), dateFrom, dateTo, true, true, true
        );
        var job = syncJobService.create(request);
        syncOrchestrator.orchestrateAsync(job.getId(), request);
        return SyncJobResponse.from(job);
    }

    private static String formatDuration(Instant start, Instant end) {
        if (end == null) {
            return "в процессе";
        }
        long secs = ChronoUnit.SECONDS.between(start, end);
        if (secs < 60) {
            return secs + " с";
        }
        return (secs / 60) + " м " + (secs % 60) + " с";
    }

    private Map<String, Object> resolveUser(OAuth2AuthenticationToken authentication) {
        Map<String, Object> attrs = authentication.getPrincipal().getAttributes();
        String provider = authentication.getAuthorizedClientRegistrationId();
        String username = "github".equals(provider)
            ? (String) attrs.get("login")
            : (String) attrs.get("username");
        return Map.of(
            "name", attrs.getOrDefault("name", username),
            "username", username != null ? username : "",
            "avatarUrl", attrs.getOrDefault("avatar_url", ""),
            "provider", provider
        );
    }
}
