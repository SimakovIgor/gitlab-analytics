package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SettingsControllerTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    private GitSource savedSource;

    @BeforeEach
    void setUp() {
        savedSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("prod-gitlab")
            .baseUrl("https://git.example.com")
            .build());

        // Prevent NPE in the async backfill task triggered by some tests
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), anyLong(),
            any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
    }

    // ── GitLab Sources ──────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void createSourceReturns201AndPersistsToDb() throws Exception {
        mockMvc.perform(post("/settings/sources")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"corp-gl\",\"baseUrl\":\"https://gl.corp.com\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("corp-gl"))
            .andExpect(jsonPath("$.baseUrl").value("https://gl.corp.com"));

        assertThat(gitSourceRepository.findAll()).hasSize(2);
    }

    @Test
    @WithMockUser
    void createSourceReturns400WhenNameIsBlank() throws Exception {
        mockMvc.perform(post("/settings/sources")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"baseUrl\":\"https://gl.corp.com\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void deleteSourceReturns204AndRemovesFromDb() throws Exception {
        mockMvc.perform(delete("/settings/sources/" + savedSource.getId())
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(gitSourceRepository.findById(savedSource.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteSourceReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/sources/99999")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void deleteSourceCascadesDeleteToTrackedProject() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/sources/" + savedSource.getId())
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(gitSourceRepository.findById(savedSource.getId())).isEmpty();
        assertThat(trackedProjectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void validateTokenReturns200WhenTokenIsValid() throws Exception {
        when(gitLabApiClient.getCurrentUser(anyString(), anyString()))
            .thenReturn(new GitLabUserDto(1L, "testuser", "Test User", null, null));

        mockMvc.perform(get("/settings/sources/" + savedSource.getId() + "/token/validate")
                .session(webSession)
                .param("token", "glpat-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    @WithMockUser
    void searchProjectsReturnsMockedGitLabResults() throws Exception {
        when(gitLabApiClient.searchProjects(anyString(), anyString(), anyString()))
            .thenReturn(List.of(
                new GitLabProjectDto(1L, "backend", "org/backend", "https://git.example.com/org/backend"),
                new GitLabProjectDto(2L, "frontend", "org/frontend", "https://git.example.com/org/frontend")
            ));

        mockMvc.perform(get("/settings/sources/" + savedSource.getId() + "/projects/search")
                .session(webSession)
                .param("q", "back")
                .param("token", "glpat-test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("backend"))
            .andExpect(jsonPath("$[0].path_with_namespace").value("org/backend"));
    }

    @Test
    @WithMockUser
    void searchProjectsReturns404WhenSourceNotFound() throws Exception {
        mockMvc.perform(get("/settings/sources/99999/projects/search")
                .session(webSession)
                .param("q", "repo")
                .param("token", "glpat-test"))
            .andExpect(status().isNotFound());
    }

    // ── Tracked Projects ────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void createProjectReturns201AndStartsBackfillJob() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "gitSourceId", savedSource.getId(),
            "gitlabProjectId", 99L,
            "pathWithNamespace", "org/myrepo",
            "name", "myrepo",
            "token", "glpat-test"
        ));

        mockMvc.perform(post("/settings/projects")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("myrepo"))
            .andExpect(jsonPath("$.pathWithNamespace").value("org/myrepo"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.jobId").isNumber());

        assertThat(trackedProjectRepository.findAll()).hasSize(1);
        assertThat(syncJobRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser
    void createProjectReturns404WhenGitSourceNotFound() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "gitSourceId", 99_999L,
            "gitlabProjectId", 1L,
            "pathWithNamespace", "org/repo",
            "name", "repo",
            "token", "glpat-test"
        ));

        mockMvc.perform(post("/settings/projects")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        assertThat(trackedProjectRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteProjectReturns204AndRemovesFromDb() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/projects/" + project.getId())
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(trackedProjectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteProjectReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/projects/99999")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void backfillProjectReturns200WithStartedJob() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        mockMvc.perform(post("/settings/projects/" + project.getId() + "/backfill")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isNumber())
            .andExpect(jsonPath("$.status").value("STARTED"));

        assertThat(syncJobRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser
    void backfillProjectReturns404ForUnknownProject() throws Exception {
        mockMvc.perform(post("/settings/projects/99999/backfill")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void backfillProject_whenActiveJobExists_returnsExistingJobWithoutCreatingDuplicate() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        SyncJob existingJob = syncJobRepository.save(SyncJob.builder()
            .workspaceId(testWorkspaceId)
            .status(SyncStatus.STARTED)
            .dateFrom(Instant.now().minus(365, ChronoUnit.DAYS))
            .dateTo(Instant.now())
            .payloadJson(toPayloadJson(List.of(project.getId())))
            .build());

        mockMvc.perform(post("/settings/projects/" + project.getId() + "/backfill")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(existingJob.getId()))
            .andExpect(jsonPath("$.status").value("STARTED"));

        assertThat(syncJobRepository.count()).isEqualTo(1);
    }

    private String toPayloadJson(List<Long> projectIds) {
        try {
            ManualSyncRequest payload = new ManualSyncRequest(
                projectIds,
                Instant.now().minus(365, ChronoUnit.DAYS),
                Instant.now(),
                true, true, true
            );
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize payload", e);
        }
    }

    // ── Tracked Users ────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void createUsersBulkReturns201AndPersistsAllUsers() throws Exception {
        String body = objectMapper.writeValueAsString(List.of(
            Map.of("displayName", "Alice", "email", "alice@example.com"),
            Map.of("displayName", "Bob", "email", "bob@example.com"),
            Map.of("displayName", "Charlie")
        ));

        mockMvc.perform(post("/settings/users/bulk")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created.length()").value(3))
            .andExpect(jsonPath("$.created[0].displayName").value("Alice"))
            .andExpect(jsonPath("$.created[1].displayName").value("Bob"))
            .andExpect(jsonPath("$.created[2].displayName").value("Charlie"));

        assertThat(trackedUserRepository.findAll()).hasSize(3);
    }

    @Test
    @WithMockUser
    void createUserReturns201AndPersistsToDb() throws Exception {
        mockMvc.perform(post("/settings/users")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\",\"email\":\"bob@example.com\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.displayName").value("Bob"))
            .andExpect(jsonPath("$.email").value("bob@example.com"))
            .andExpect(jsonPath("$.enabled").value(true));

        assertThat(trackedUserRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser
    void createUserWithoutEmailReturns201() throws Exception {
        mockMvc.perform(post("/settings/users")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Bob\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.displayName").value("Bob"));

        assertThat(trackedUserRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser
    void createUserReturns400WhenDisplayNameIsBlank() throws Exception {
        mockMvc.perform(post("/settings/users")
                .session(webSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"\",\"email\":\"bob@example.com\"}"))
            .andExpect(status().isBadRequest());

        assertThat(trackedUserRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteUserReturns204AndRemovesFromDb() throws Exception {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Charlie")
            .email("charlie@example.com")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/users/" + user.getId())
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(trackedUserRepository.findById(user.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteUserReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/users/99999")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ── Workspace isolation ──────────────────────────────────────────────────

    @Test
    @WithMockUser
    void deleteSourceReturns404ForSourceInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspaceId).name("other-gl").baseUrl("https://other.com").build());

        mockMvc.perform(delete("/settings/sources/" + otherSource.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());

        assertThat(gitSourceRepository.findById(otherSource.getId())).isPresent();
    }

    @Test
    @WithMockUser
    void deleteProjectReturns404ForProjectInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspaceId).name("other-gl").baseUrl("https://other.com").build());
        TrackedProject otherProject = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(otherWorkspaceId).gitSourceId(otherSource.getId())
            .gitlabProjectId(99L).pathWithNamespace("other/repo").name("other-repo")
            .tokenEncrypted("tok").enabled(true).build());

        mockMvc.perform(delete("/settings/projects/" + otherProject.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());

        assertThat(trackedProjectRepository.findById(otherProject.getId())).isPresent();
    }

    @Test
    @WithMockUser
    void deleteUserReturns404ForUserInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        TrackedUser otherUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(otherWorkspaceId).displayName("Secret User")
            .email("secret@other.com").enabled(true).build());

        mockMvc.perform(delete("/settings/users/" + otherUser.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());

        assertThat(trackedUserRepository.findById(otherUser.getId())).isPresent();
    }

    @Test
    @WithMockUser
    void backfillProjectReturns404ForProjectInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspaceId).name("other-gl").baseUrl("https://other.com").build());
        TrackedProject otherProject = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(otherWorkspaceId).gitSourceId(otherSource.getId())
            .gitlabProjectId(99L).pathWithNamespace("other/repo").name("other-repo")
            .tokenEncrypted("tok").enabled(true).build());

        mockMvc.perform(post("/settings/projects/" + otherProject.getId() + "/backfill")
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void linkGitlabAccountReturns404ForUserInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        TrackedUser otherUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(otherWorkspaceId).displayName("Other User")
            .email("other@example.com").enabled(true).build());

        mockMvc.perform(post("/settings/users/" + otherUser.getId() + "/link-gitlab")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gitlabUserId\":42,\"username\":\"other\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void createProjectReturns404WhenGitSourceBelongsToAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspaceId).name("other-gl").baseUrl("https://other.com").build());

        String body = objectMapper.writeValueAsString(Map.of(
            "gitSourceId", otherSource.getId(),
            "gitlabProjectId", 1L,
            "pathWithNamespace", "org/repo",
            "name", "repo",
            "token", "glpat-test"
        ));

        mockMvc.perform(post("/settings/projects")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void linkGitlabAccountAllowsLinkingGitlabUserIdAlreadyUsedInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        TrackedUser otherUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(otherWorkspaceId).displayName("Other User")
            .email("other@example.com").enabled(true).build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(otherUser.getId()).gitlabUserId(42L).build());

        TrackedUser ownUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId).displayName("Own User")
            .email("own@example.com").enabled(true).build());

        mockMvc.perform(post("/settings/users/" + ownUser.getId() + "/link-gitlab")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gitlabUserId\":42,\"username\":\"ownuser\"}"))
            .andExpect(status().isNoContent());

        assertThat(aliasRepository.findByTrackedUserId(ownUser.getId()))
            .extracting(TrackedUserAlias::getGitlabUserId)
            .contains(42L);
    }

    @Test
    @WithMockUser
    void searchUsersReturns404WhenSourceBelongsToAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspaceId).name("other-gl").baseUrl("https://other.com").build());
        trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(otherWorkspaceId).gitSourceId(otherSource.getId())
            .gitlabProjectId(99L).pathWithNamespace("other/repo").name("other-repo")
            .tokenEncrypted("tok").enabled(true).build());

        mockMvc.perform(get("/settings/sources/" + otherSource.getId() + "/users/search")
                .session(webSession).param("q", "alice"))
            .andExpect(status().isNotFound());
    }

    private Long createOtherWorkspaceId() {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(99L).githubLogin("other-owner").lastLoginAt(Instant.now()).build());
        Workspace otherWorkspace = workspaceRepository.save(Workspace.builder()
            .name("Other WS").slug("other-ws").ownerId(otherOwner.getId()).plan("FREE").apiToken("other-tok").build());
        return otherWorkspace.getId();
    }

    // ── Sync status ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void getSyncStatusReturns200ForExistingJob() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        String backfillResult = mockMvc.perform(post("/settings/projects/" + project.getId() + "/backfill")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        Long jobId = objectMapper.readTree(backfillResult).get("jobId").asLong();

        mockMvc.perform(get("/settings/sync/" + jobId)
                .session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId));
    }

    @Test
    @WithMockUser
    void getSyncStatusReturns404ForUnknownJob() throws Exception {
        mockMvc.perform(get("/settings/sync/99999")
                .session(webSession))
            .andExpect(status().isNotFound());
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Test
    void settingsPageRedirectsToLoginWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/settings"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void settingsMutationEndpointsRedirectToLoginWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/settings/sources")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"baseUrl\":\"https://x.com\"}"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(delete("/settings/users/1")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }
}
