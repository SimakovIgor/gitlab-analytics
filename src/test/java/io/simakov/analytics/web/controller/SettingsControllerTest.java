package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
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
@SuppressWarnings({"PMD.JUnitTestContainsTooManyAsserts", "PMD.JUnitTestsShouldIncludeAssert"})
class SettingsControllerTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    private GitSource savedSource;

    @BeforeEach
    void setUp() {
        savedSource = gitSourceRepository.save(GitSource.builder()
            .name("prod-gitlab")
            .baseUrl("https://git.example.com")
            .tokenEncrypted("tok")
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"corp-gl\",\"baseUrl\":\"https://gl.corp.com\",\"token\":\"glpat-abc\"}"))
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"baseUrl\":\"https://gl.corp.com\",\"token\":\"tok\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void deleteSourceReturns204AndRemovesFromDb() throws Exception {
        mockMvc.perform(delete("/settings/sources/" + savedSource.getId())
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(gitSourceRepository.findById(savedSource.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteSourceReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/sources/99999")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void deleteSourceCascadesDeleteToTrackedProject() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/sources/" + savedSource.getId())
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(gitSourceRepository.findById(savedSource.getId())).isEmpty();
        assertThat(trackedProjectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void testSourceReturns200WithMockedGitLabUser() throws Exception {
        when(gitLabApiClient.getCurrentUser(anyString(), anyString()))
            .thenReturn(new GitLabUserDto(42L, "alice", "Alice Smith", null, null));

        mockMvc.perform(post("/settings/sources/" + savedSource.getId() + "/test")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.name").value("Alice Smith"));
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
                .param("q", "back"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("backend"))
            .andExpect(jsonPath("$[0].path_with_namespace").value("org/backend"));
    }

    @Test
    @WithMockUser
    void searchProjectsReturns404WhenSourceNotFound() throws Exception {
        mockMvc.perform(get("/settings/sources/99999/projects/search")
                .param("q", "repo"))
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
            "name", "myrepo"
        ));

        mockMvc.perform(post("/settings/projects")
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
            "name", "repo"
        ));

        mockMvc.perform(post("/settings/projects")
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
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/projects/" + project.getId())
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(trackedProjectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteProjectReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/projects/99999")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void backfillProjectReturns200WithStartedJob() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .enabled(true)
            .build());

        mockMvc.perform(post("/settings/projects/" + project.getId() + "/backfill")
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
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ── Tracked Users ────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void createUserReturns201AndPersistsToDb() throws Exception {
        mockMvc.perform(post("/settings/users")
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
            .displayName("Charlie")
            .email("charlie@example.com")
            .enabled(true)
            .build());

        mockMvc.perform(delete("/settings/users/" + user.getId())
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(trackedUserRepository.findById(user.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteUserReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/users/99999")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ── Sync status ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void getSyncStatusReturns200ForExistingJob() throws Exception {
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(savedSource.getId())
            .gitlabProjectId(10L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .enabled(true)
            .build());

        String backfillResult = mockMvc.perform(post("/settings/projects/" + project.getId() + "/backfill")
                .with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        Long jobId = objectMapper.readTree(backfillResult).get("jobId").asLong();

        mockMvc.perform(get("/settings/sync/" + jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId));
    }

    @Test
    @WithMockUser
    void getSyncStatusReturns404ForUnknownJob() throws Exception {
        mockMvc.perform(get("/settings/sync/99999"))
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
                .content("{\"name\":\"x\",\"baseUrl\":\"https://x.com\",\"token\":\"t\"}"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(delete("/settings/users/1")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }
}
