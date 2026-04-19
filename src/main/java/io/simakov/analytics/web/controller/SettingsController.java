package io.simakov.analytics.web.controller;

import io.simakov.analytics.api.dto.request.CreateGitSourceRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.SettingsService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.CreatedProjectResult;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import io.simakov.analytics.web.dto.SettingsPageData;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final SettingsViewService settingsViewService;
    private final OAuth2UserResolver userResolver;

    // ── Settings page ────────────────────────────────────────────────────────

    @GetMapping
    public String settings(OAuth2AuthenticationToken authentication,
                           Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        SettingsPageData data = settingsViewService.buildSettingsPage();
        model.addAttribute("sources", data.sources());
        model.addAttribute("projects", data.projects());
        model.addAttribute("sourceNames", data.sourceNames());
        model.addAttribute("usersWithAliases", data.usersWithAliases());
        model.addAttribute("onboardingMode", data.onboardingMode());
        model.addAttribute("hasSources", data.hasSources());
        model.addAttribute("hasProjects", data.hasProjects());
        model.addAttribute("hasUsers", data.hasUsers());
        model.addAttribute("activeJobIds", data.activeJobIds());
        model.addAttribute("recentJobs", data.recentJobs());

        return "settings";
    }

    // ── GitLab Sources ──────────────────────────────────────────────────────

    @PostMapping("/sources")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSource(@RequestBody @Valid CreateGitSourceRequest request) {
        GitSource source = settingsService.createSource(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", source.getId(),
            "name", source.getName(),
            "baseUrl", source.getBaseUrl()
        ));
    }

    @DeleteMapping("/sources/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        settingsService.deleteSource(id);
        return ResponseEntity.noContent().build();
    }

    // ── Tracked Projects ────────────────────────────────────────────────────

    @GetMapping("/sources/{id}/token/validate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> validateToken(@PathVariable Long id,
                                                             @RequestParam String token) {
        return ResponseEntity.ok(settingsService.validateToken(id, token));
    }

    @GetMapping("/sources/{id}/projects/search")
    @ResponseBody
    public List<GitLabProjectDto> searchProjects(@PathVariable Long id,
                                                 @RequestParam String q,
                                                 @RequestParam String token) {
        return settingsService.searchProjects(id, q, token);
    }

    @PostMapping("/projects")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createProject(@RequestBody @Valid CreateTrackedProjectRequest request) {
        CreatedProjectResult result = settingsService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", result.project().getId(),
            "name", result.project().getName(),
            "pathWithNamespace", result.project().getPathWithNamespace(),
            "enabled", result.project().isEnabled(),
            "jobId", result.jobId()
        ));
    }

    @DeleteMapping("/projects/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        settingsService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{id}/backfill")
    @ResponseBody
    public SyncJobResponse backfillProject(@PathVariable Long id) {
        return settingsService.backfillProject(id);
    }

    // ── Contributor discovery ────────────────────────────────────────────────

    @GetMapping("/sources/{id}/users/search")
    @ResponseBody
    public List<GitLabUserSearchDto> searchUsers(@PathVariable Long id,
                                                 @RequestParam String q) {
        return settingsService.searchUsers(id, q);
    }

    @GetMapping("/users/discovered")
    @ResponseBody
    public List<DiscoveredContributor> discoverContributors() {
        return settingsService.discoverContributors();
    }

    // ── Tracked Users ────────────────────────────────────────────────────────

    @PostMapping("/users/bulk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUsersBulk(
        @RequestBody List<CreateTrackedUserRequest> requests) {
        List<Map<String, Object>> created = settingsService.createUsersBulk(requests).stream()
            .map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "displayName", u.getDisplayName(),
                "email", u.getEmail() != null
                    ? u.getEmail()
                    : ""
            ))
            .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("created", created));
    }

    @PostMapping("/users")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody @Valid CreateTrackedUserRequest request) {
        TrackedUser saved = settingsService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", saved.getId(),
            "displayName", saved.getDisplayName(),
            "email", saved.getEmail() != null
                ? saved.getEmail()
                : "",
            "enabled", saved.isEnabled()
        ));
    }

    @PostMapping("/users/{id}/link-gitlab")
    @ResponseBody
    public ResponseEntity<Void> linkGitlabAccount(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        Object raw = body.get("gitlabUserId");
        if (raw == null) {
            return ResponseEntity.badRequest().build();
        }
        Long gitlabUserId;
        try {
            gitlabUserId = Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
        String username = Objects.toString(body.get("username"), null);
        settingsService.linkGitlabAccount(id, gitlabUserId, username);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        settingsService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── Snapshot backfill ────────────────────────────────────────────────────

    @PostMapping("/snapshots/backfill")
    @ResponseBody
    public ResponseEntity<Void> triggerSnapshotBackfill() {
        settingsService.triggerSnapshotBackfill();
        return ResponseEntity.accepted().build();
    }

    // ── Sync status polling ──────────────────────────────────────────────────

    @GetMapping("/sync/{jobId}")
    @ResponseBody
    public SyncJobResponse getSyncStatus(@PathVariable Long jobId) {
        return settingsService.getSyncStatus(jobId);
    }

    @PostMapping("/sync/{jobId}/retry")
    @ResponseBody
    public SyncJobResponse retrySync(@PathVariable Long jobId) {
        return settingsService.retrySync(jobId);
    }
}
