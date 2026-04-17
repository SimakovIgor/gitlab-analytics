package io.simakov.analytics.web.controller;

import io.simakov.analytics.api.dto.request.CreateGitSourceRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.api.mapper.GitSourceMapper;
import io.simakov.analytics.api.mapper.TrackedProjectMapper;
import io.simakov.analytics.api.mapper.TrackedUserMapper;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.util.DateTimeUtils;
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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final int BACKFILL_DAYS = 360;

    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;
    private final GitSourceMapper gitSourceMapper;
    private final TrackedProjectMapper trackedProjectMapper;
    private final TrackedUserMapper trackedUserMapper;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;

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

        model.addAttribute("sources", sources);
        model.addAttribute("projects", projects);
        model.addAttribute("sourceNames", sourceNames);
        model.addAttribute("usersWithAliases", usersWithAliases);
        return "settings";
    }

    // ── GitLab Sources ──────────────────────────────────────────────────────

    @PostMapping("/sources")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSource(@RequestBody @Valid CreateGitSourceRequest request) {
        GitSource source = GitSource.builder()
            .name(request.name())
            .baseUrl(request.baseUrl().stripTrailing())
            .tokenEncrypted(encryptionService.encrypt(request.token()))
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

    @PostMapping("/sources/{id}/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testSource(@PathVariable Long id) {
        GitSource source = gitSourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        String token = encryptionService.decrypt(source.getTokenEncrypted());
        var user = gitLabApiClient.getCurrentUser(source.getBaseUrl(), token);
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "username", user.username(),
            "name", user.name()
        ));
    }

    @GetMapping("/sources/{id}/projects/search")
    @ResponseBody
    public List<GitLabProjectDto> searchProjects(@PathVariable Long id,
                                                 @RequestParam String q) {
        GitSource source = gitSourceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", id));
        String token = encryptionService.decrypt(source.getTokenEncrypted());
        return gitLabApiClient.searchProjects(source.getBaseUrl(), token, q);
    }

    // ── Tracked Projects ────────────────────────────────────────────────────

    @PostMapping("/projects")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createProject(@RequestBody @Valid CreateTrackedProjectRequest request) {
        if (!gitSourceRepository.existsById(request.gitSourceId())) {
            throw new ResourceNotFoundException("GitSource", request.gitSourceId());
        }
        TrackedProject saved = trackedProjectRepository.save(trackedProjectMapper.toEntity(request));

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
