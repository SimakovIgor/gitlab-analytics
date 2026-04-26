package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.CreateGitSourceRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.SettingsService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.CreatedProjectResult;
import io.simakov.analytics.web.dto.DiscoveredContributor;
import io.simakov.analytics.web.dto.MemberDto;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.web.dto.TeamDto;
import io.simakov.analytics.workspace.MembersService;
import io.simakov.analytics.workspace.TeamService;
import io.simakov.analytics.workspace.WorkspacePermissionService;
import io.simakov.analytics.workspace.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final MembersService membersService;
    private final TeamService teamService;
    private final WorkspacePermissionService permissionService;
    private final ObjectMapper objectMapper;

    // ── Settings page ────────────────────────────────────────────────────────

    @GetMapping
    public String settings(Model model) {
        if (!permissionService.isOwner()) {
            return "redirect:/";
        }
        SettingsPageData data = settingsViewService.buildSettingsPage();
        model.addAttribute("sources", data.sources());
        model.addAttribute("projects", data.projects());
        model.addAttribute("allProjects", data.projects());
        model.addAttribute("sourceNames", data.sourceNames());
        model.addAttribute("usersWithAliases", data.usersWithAliases());
        model.addAttribute("onboardingMode", data.onboardingMode());
        model.addAttribute("hasSources", data.hasSources());
        model.addAttribute("hasProjects", data.hasProjects());
        model.addAttribute("hasUsers", data.hasUsers());
        model.addAttribute("activeJobIds", data.activeJobIds());
        model.addAttribute("recentJobs", data.recentJobs());
        model.addAttribute("showInactive", false);
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(WorkspaceContext.get()));

        Long workspaceId = WorkspaceContext.get();
        List<MemberDto> members = membersService.listMembers(workspaceId);
        model.addAttribute("members", members);

        String allProjectsJson = buildProjectsJson(data);
        model.addAttribute("allProjectsJson", allProjectsJson);

        return "settings";
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private String buildProjectsJson(SettingsPageData data) {
        try {
            List<Map<String, Object>> list = data.projects().stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName()))
                .toList();
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    // ── Members ──────────────────────────────────────────────────────────────

    @PostMapping("/members/invite")
    @ResponseBody
    public ResponseEntity<Map<String, String>> createInvite(@AuthenticationPrincipal AppUserPrincipal principal) {
        permissionService.requireOwner();
        Long workspaceId = WorkspaceContext.get();
        WorkspaceInvite invite = membersService.createInvite(workspaceId, principal.getAppUser().getId());
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String link = baseUrl + "/join?token=" + invite.getToken();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("link", link));
    }

    @DeleteMapping("/members/{appUserId}")
    @ResponseBody
    public ResponseEntity<Void> removeMember(@PathVariable Long appUserId,
                                             @AuthenticationPrincipal AppUserPrincipal principal) {
        permissionService.requireOwner();
        Long workspaceId = WorkspaceContext.get();
        // Owner cannot be removed
        if (appUserId.equals(principal.getAppUser().getId())) {
            return ResponseEntity.badRequest().build();
        }
        membersService.removeMember(workspaceId, appUserId);
        return ResponseEntity.noContent().build();
    }

    // ── GitLab Sources ──────────────────────────────────────────────────────

    @PostMapping("/sources")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSource(@RequestBody @Valid CreateGitSourceRequest request) {
        permissionService.requireOwner();
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
        permissionService.requireOwner();
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
        permissionService.requireOwner();
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
        permissionService.requireOwner();
        settingsService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{id}/backfill")
    @ResponseBody
    public SyncJobResponse backfillProject(@PathVariable Long id) {
        permissionService.requireOwner();
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

    @GetMapping("/users/tracked")
    @ResponseBody
    public List<Map<String, Object>> getTrackedUsers() {
        return settingsService.getTrackedUsersWithMrCount();
    }

    // ── Tracked Users ────────────────────────────────────────────────────────

    @PostMapping("/users/bulk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUsersBulk(
        @RequestBody List<CreateTrackedUserRequest> requests) {
        permissionService.requireOwner();
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
        permissionService.requireOwner();
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
        permissionService.requireOwner();
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
        permissionService.requireOwner();
        settingsService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── Snapshot backfill ────────────────────────────────────────────────────

    @PostMapping("/snapshots/backfill")
    @ResponseBody
    public ResponseEntity<Void> triggerSnapshotBackfill() {
        permissionService.requireOwner();
        settingsService.scheduleSnapshotBackfill();
        return ResponseEntity.accepted().build();
    }

    /**
     * Synchronous backfill for onboarding — blocks until all snapshots are created and returns the count.
     * Kept separate from the async endpoint so no existing callers are affected.
     */
    @PostMapping("/snapshots/backfill/sync")
    @ResponseBody
    public ResponseEntity<Map<String, Integer>> triggerSnapshotBackfillSync() {
        permissionService.requireOwner();
        int created = settingsService.triggerSnapshotBackfill();
        return ResponseEntity.ok(Map.of("snapshotsCreated", created));
    }

    // ── Teams ────────────────────────────────────────────────────────────────

    @GetMapping("/teams")
    @ResponseBody
    public List<TeamDto> listTeams() {
        return teamService.listTeams(WorkspaceContext.get());
    }

    @PostMapping("/teams")
    @ResponseBody
    public ResponseEntity<TeamDto> createTeam(@RequestBody Map<String, Object> body) {
        permissionService.requireOwner();
        String name = Objects.toString(body.get("name"), "").trim();
        int colorIndex = body.containsKey("colorIndex")
            ? ((Number) body.get("colorIndex")).intValue()
            : 1;
        List<Long> memberIds = parseMemberIds(body);
        List<Long> projectIds = parseProjectIds(body);
        TeamDto team = teamService.createTeam(WorkspaceContext.get(), name, colorIndex, memberIds, projectIds);
        return ResponseEntity.status(HttpStatus.CREATED).body(team);
    }

    @PutMapping("/teams/{id}")
    @ResponseBody
    public ResponseEntity<TeamDto> updateTeam(@PathVariable Long id,
                                              @RequestBody Map<String, Object> body) {
        permissionService.requireOwner();
        String name = Objects.toString(body.get("name"), "").trim();
        int colorIndex = body.containsKey("colorIndex")
            ? ((Number) body.get("colorIndex")).intValue()
            : 1;
        List<Long> memberIds = parseMemberIds(body);
        List<Long> projectIds = parseProjectIds(body);
        TeamDto team = teamService.updateTeam(WorkspaceContext.get(), id, name, colorIndex, memberIds, projectIds);
        return ResponseEntity.ok(team);
    }

    @DeleteMapping("/teams/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteTeam(@PathVariable Long id) {
        permissionService.requireOwner();
        teamService.deleteTeam(WorkspaceContext.get(), id);
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> parseMemberIds(Map<String, Object> body) {
        Object raw = body.get("memberIds");
        if (raw instanceof List<?> list) {
            return ((List<Number>) list).stream().map(Number::longValue).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> parseProjectIds(Map<String, Object> body) {
        Object raw = body.get("projectIds");
        if (raw instanceof List<?> list) {
            return ((List<Number>) list).stream().map(Number::longValue).toList();
        }
        return List.of();
    }

    // ── Sync status polling ──────────────────────────────────────────────────

    @GetMapping("/sync/progress/{jobId}")
    public String syncProgressPage(@PathVariable Long jobId,
                                   @RequestParam(required = false) String name,
                                   Model model) {
        model.addAttribute("jobId", jobId);
        model.addAttribute("projectName", name != null
            ? name
            : "репозиторий");
        return "sync-progress";
    }

    @GetMapping("/sync/{jobId}")
    @ResponseBody
    public SyncJobResponse getSyncStatus(@PathVariable Long jobId) {
        return settingsService.getSyncStatus(jobId);
    }

    @PostMapping("/sync/{jobId}/retry")
    @ResponseBody
    public SyncJobResponse retrySync(@PathVariable Long jobId) {
        permissionService.requireOwner();
        return settingsService.retrySync(jobId);
    }

    // ── Digest settings ──────────────────────────────────────────────────────

    @PostMapping("/digest/toggle")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleDigest() {
        permissionService.requireOwner();
        Long workspaceId = WorkspaceContext.get();
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new IllegalStateException("Workspace not found"));
        workspace.setDigestEnabled(!workspace.isDigestEnabled());
        workspaceRepository.save(workspace);
        return ResponseEntity.ok(Map.of("digestEnabled", workspace.isDigestEnabled()));
    }

    @GetMapping("/digest/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> digestStatus() {
        Long workspaceId = WorkspaceContext.get();
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new IllegalStateException("Workspace not found"));
        return ResponseEntity.ok(Map.of("digestEnabled", workspace.isDigestEnabled()));
    }
}
