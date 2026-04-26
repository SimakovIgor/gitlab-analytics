package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TeamProject;
import io.simakov.analytics.domain.model.TeamProjectId;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TeamProjectRepository;
import io.simakov.analytics.domain.repository.TeamRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for team-project association (team_project junction table).
 * Covers the new projectIds field in create/update/list team endpoints.
 */
class TeamProjectTest extends BaseIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamProjectRepository teamProjectRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Long projectId1;
    private Long projectId2;

    @BeforeEach
    void setUpProjects() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl")
            .baseUrl("https://git.example.com")
            .build());

        projectId1 = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId).gitSourceId(source.getId())
            .gitlabProjectId(1L).pathWithNamespace("org/repo-a").name("repo-a")
            .tokenEncrypted("tok").enabled(true).build()).getId();

        projectId2 = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId).gitSourceId(source.getId())
            .gitlabProjectId(2L).pathWithNamespace("org/repo-b").name("repo-b")
            .tokenEncrypted("tok").enabled(true).build()).getId();
    }

    // ── listTeams includes projectIds ─────────────────────────────────────────

    @Test
    @WithMockUser
    void listTeamsReturnsEmptyProjectIdsWhenNoneAssigned() throws Exception {
        teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Backend").colorIndex(1).build());

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].projectIds").isArray())
            .andExpect(jsonPath("$[0].projectIds.length()").value(0));
    }

    @Test
    @WithMockUser
    void listTeamsReturnsAssignedProjectIds() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Backend").colorIndex(1).build());
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId1));
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId2));

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].projectIds.length()").value(2));
    }

    // ── createTeam with projectIds ────────────────────────────────────────────

    @Test
    @WithMockUser
    void createTeamPersistsProjectAssociations() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Platform",
            "colorIndex", 2,
            "memberIds", List.of(),
            "projectIds", List.of(projectId1, projectId2)
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.projectIds.length()").value(2));

        Long teamId = teamRepository.findByWorkspaceId(testWorkspaceId).get(0).getId();
        List<Long> stored = teamProjectRepository.findProjectIdsByTeamId(teamId);
        assertThat(stored).containsExactlyInAnyOrder(projectId1, projectId2);
    }

    @Test
    @WithMockUser
    void createTeamWithNoProjectIdsStoresNone() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Infra",
            "colorIndex", 1,
            "memberIds", List.of(),
            "projectIds", List.of()
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.projectIds.length()").value(0));

        Long teamId = teamRepository.findByWorkspaceId(testWorkspaceId).get(0).getId();
        assertThat(teamProjectRepository.findProjectIdsByTeamId(teamId)).isEmpty();
    }

    @Test
    @WithMockUser
    void createTeamWithoutProjectIdsFieldStoresNone() throws Exception {
        // projectIds key absent — should default to empty
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Mobile",
            "colorIndex", 3,
            "memberIds", List.of()
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.projectIds.length()").value(0));
    }

    // ── updateTeam with projectIds ────────────────────────────────────────────

    @Test
    @WithMockUser
    void updateTeamAddsProjectAssociations() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Alpha").colorIndex(1).build());

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Alpha",
            "colorIndex", 1,
            "memberIds", List.of(),
            "projectIds", List.of(projectId1)
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectIds.length()").value(1));

        assertThat(teamProjectRepository.findProjectIdsByTeamId(team.getId()))
            .containsExactly(projectId1);
    }

    @Test
    @WithMockUser
    void updateTeamReplacesProjectAssociations() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Alpha").colorIndex(1).build());
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId1));

        // Replace projectId1 with projectId2
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Alpha",
            "colorIndex", 1,
            "memberIds", List.of(),
            "projectIds", List.of(projectId2)
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectIds.length()").value(1));

        List<Long> stored = teamProjectRepository.findProjectIdsByTeamId(team.getId());
        assertThat(stored).containsExactly(projectId2);
        assertThat(stored).doesNotContain(projectId1);
    }

    @Test
    @WithMockUser
    void updateTeamClearsProjectAssociationsWhenEmptyList() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Solo").colorIndex(1).build());
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId1));
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId2));

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Solo",
            "colorIndex", 1,
            "memberIds", List.of(),
            "projectIds", List.of()
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectIds.length()").value(0));

        assertThat(teamProjectRepository.findProjectIdsByTeamId(team.getId())).isEmpty();
    }

    // ── deleteTeam cascades to team_project ───────────────────────────────────

    @Test
    @WithMockUser
    void deleteTeamRemovesProjectAssociations() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("ToDelete").colorIndex(1).build());
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId1));
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId2));

        mockMvc.perform(delete("/settings/teams/" + team.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(teamProjectRepository.existsById(new TeamProjectId(team.getId(), projectId1))).isFalse();
        assertThat(teamProjectRepository.existsById(new TeamProjectId(team.getId(), projectId2))).isFalse();
    }

    // ── workspace isolation ───────────────────────────────────────────────────

    @Test
    @WithMockUser
    void projectsFromOtherWorkspaceAreNotReturnedInList() throws Exception {
        // Create another workspace with its own team
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(99L).githubLogin("other").lastLoginAt(Instant.now()).build());
        Workspace otherWs = workspaceRepository.save(Workspace.builder()
            .name("Other").slug("other").ownerId(otherOwner.getId())
            .plan("FREE").apiToken("other-tok").build());
        Team otherTeam = teamRepository.save(Team.builder()
            .workspaceId(otherWs.getId()).name("Shadow").colorIndex(1).build());
        // Associate our project with the other workspace's team
        teamProjectRepository.save(TeamProject.of(otherTeam.getId(), projectId1));

        // Our workspace team has no projects
        teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Mine").colorIndex(1).build());

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Mine"))
            .andExpect(jsonPath("$[0].projectIds.length()").value(0));
    }

    // ── multiple teams sharing a repository (monolith case) ──────────────────

    @Test
    @WithMockUser
    void multipleTeamsCanShareTheSameRepository() throws Exception {
        String bodyA = objectMapper.writeValueAsString(Map.of(
            "name", "TeamA", "colorIndex", 1,
            "memberIds", List.of(), "projectIds", List.of(projectId1)
        ));
        String bodyB = objectMapper.writeValueAsString(Map.of(
            "name", "TeamB", "colorIndex", 2,
            "memberIds", List.of(), "projectIds", List.of(projectId1)
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(bodyA))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(bodyB))
            .andExpect(status().isCreated());

        List<Team> teams = teamRepository.findByWorkspaceId(testWorkspaceId);
        assertThat(teams).hasSize(2);
        for (Team t : teams) {
            assertThat(teamProjectRepository.findProjectIdsByTeamId(t.getId()))
                .containsExactly(projectId1);
        }
    }

    // ── /compare page smoke tests ─────────────────────────────────────────────

    @Test
    void comparePageLoadsWithTeamsAndProjects() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Alpha").colorIndex(1).build());
        teamProjectRepository.save(TeamProject.of(team.getId(), projectId1));
        createUser("dev@test.com", "Dev", team.getId());

        mockMvc.perform(get("/compare").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    void comparePageLoadsWhenTeamHasNoProjectsAssigned() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("NoProjects").colorIndex(2).build());
        createUser("dev@test.com", "Dev", team.getId());

        mockMvc.perform(get("/compare").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    void comparePageLoadsWhenNoTeamsExist() throws Exception {
        mockMvc.perform(get("/compare").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TrackedUser createUser(String email, String displayName, Long teamId) {
        return trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .email(email)
            .displayName(displayName)
            .teamId(teamId)
            .enabled(true)
            .build());
    }
}
