package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.TeamRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TeamSettingsControllerTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    // ── GET /settings/teams ──────────────────────────────────────────────────

    @Test
    @WithMockUser
    void listTeamsReturnsEmptyArrayWhenNoTeamsExist() throws Exception {
        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser
    void listTeamsReturnsCreatedTeams() throws Exception {
        teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Backend").colorIndex(1).build());
        teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Frontend").colorIndex(3).build());

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Backend"))
            .andExpect(jsonPath("$[1].name").value("Frontend"));
    }

    @Test
    @WithMockUser
    void listTeamsIncludesMembersInResponse() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Alpha").colorIndex(2).build());
        TrackedUser user = createUser("alice@test.com", "Alice", team.getId());

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].memberCount").value(1))
            .andExpect(jsonPath("$[0].members[0].id").value(user.getId()))
            .andExpect(jsonPath("$[0].members[0].displayName").value("Alice"));
    }

    @Test
    @WithMockUser
    void listTeamsDoesNotReturnTeamsFromAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        teamRepository.save(Team.builder()
            .workspaceId(otherWorkspaceId).name("Other Team").colorIndex(1).build());
        teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("My Team").colorIndex(1).build());

        mockMvc.perform(get("/settings/teams").session(webSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("My Team"));
    }

    // ── POST /settings/teams ─────────────────────────────────────────────────

    @Test
    @WithMockUser
    void createTeamReturns201AndPersistsToDb() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Platform",
            "colorIndex", 4,
            "memberIds", List.of()
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Platform"))
            .andExpect(jsonPath("$.colorIndex").value(4))
            .andExpect(jsonPath("$.memberCount").value(0));

        assertThat(teamRepository.findByWorkspaceId(testWorkspaceId)).hasSize(1);
    }

    @Test
    @WithMockUser
    void createTeamAssignsMembersOnCreation() throws Exception {
        TrackedUser alice = createUser("alice@test.com", "Alice", null);
        TrackedUser bob = createUser("bob@test.com", "Bob", null);

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Alpha",
            "colorIndex", 1,
            "memberIds", List.of(alice.getId(), bob.getId())
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.memberCount").value(2))
            .andExpect(jsonPath("$.members.length()").value(2));

        Long teamId = teamRepository.findByWorkspaceId(testWorkspaceId).get(0).getId();
        List<TrackedUser> members = trackedUserRepository
            .findByWorkspaceIdAndTeamIdIn(testWorkspaceId, List.of(teamId));
        assertThat(members).extracting(TrackedUser::getId)
            .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    @Test
    @WithMockUser
    void createTeamWithDefaultColorIndex() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Squad",
            "memberIds", List.of()
        ));

        mockMvc.perform(post("/settings/teams")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.colorIndex").value(1));
    }

    // ── PUT /settings/teams/{id} ─────────────────────────────────────────────

    @Test
    @WithMockUser
    void updateTeamReturns200AndPersistsChanges() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Old Name").colorIndex(1).build());

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "New Name",
            "colorIndex", 5,
            "memberIds", List.of()
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Name"))
            .andExpect(jsonPath("$.colorIndex").value(5));

        Team updated = teamRepository.findByWorkspaceIdAndId(testWorkspaceId, team.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getColorIndex()).isEqualTo(5);
    }

    @Test
    @WithMockUser
    void updateTeamReplacesMemberSet() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Alpha").colorIndex(1).build());
        TrackedUser alice = createUser("alice@test.com", "Alice", team.getId());
        TrackedUser bob = createUser("bob@test.com", "Bob", null);

        // Update: remove Alice, add Bob
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Alpha",
            "colorIndex", 1,
            "memberIds", List.of(bob.getId())
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberCount").value(1));

        List<TrackedUser> members = trackedUserRepository
            .findByWorkspaceIdAndTeamIdIn(testWorkspaceId, List.of(team.getId()));
        assertThat(members).extracting(TrackedUser::getId).containsExactly(bob.getId());

        // Alice's team_id must be null now
        TrackedUser aliceUpdated = trackedUserRepository.findById(alice.getId()).orElseThrow();
        assertThat(aliceUpdated.getTeamId()).isNull();
    }

    @Test
    @WithMockUser
    void updateTeamClearsMembersWhenEmptyList() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Solo").colorIndex(1).build());
        TrackedUser user = createUser("dev@test.com", "Dev", team.getId());

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Solo",
            "colorIndex", 1,
            "memberIds", List.of()
        ));

        mockMvc.perform(put("/settings/teams/" + team.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberCount").value(0));

        TrackedUser updated = trackedUserRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getTeamId()).isNull();
    }

    @Test
    @WithMockUser
    void updateTeamReturns404ForUnknownId() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "name", "X", "colorIndex", 1, "memberIds", List.of()));

        mockMvc.perform(put("/settings/teams/99999")
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void updateTeamReturns404ForTeamInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        Team otherTeam = teamRepository.save(Team.builder()
            .workspaceId(otherWorkspaceId).name("Other").colorIndex(1).build());

        String body = objectMapper.writeValueAsString(Map.of(
            "name", "Hacked", "colorIndex", 1, "memberIds", List.of()));

        mockMvc.perform(put("/settings/teams/" + otherTeam.getId())
                .session(webSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        Team unchanged = teamRepository.findById(otherTeam.getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Other");
    }

    // ── DELETE /settings/teams/{id} ──────────────────────────────────────────

    @Test
    @WithMockUser
    void deleteTeamReturns204AndRemovesFromDb() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("Expired").colorIndex(1).build());

        mockMvc.perform(delete("/settings/teams/" + team.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(teamRepository.findById(team.getId())).isEmpty();
    }

    @Test
    @WithMockUser
    void deleteTeamNullsOutMembersTeamId() throws Exception {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(testWorkspaceId).name("ToDelete").colorIndex(1).build());
        TrackedUser member = createUser("dev@test.com", "Dev", team.getId());

        mockMvc.perform(delete("/settings/teams/" + team.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNoContent());

        TrackedUser afterDelete = trackedUserRepository.findById(member.getId()).orElseThrow();
        assertThat(afterDelete.getTeamId()).isNull();
    }

    @Test
    @WithMockUser
    void deleteTeamReturns404ForUnknownId() throws Exception {
        mockMvc.perform(delete("/settings/teams/99999")
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void deleteTeamReturns404ForTeamInAnotherWorkspace() throws Exception {
        Long otherWorkspaceId = createOtherWorkspaceId();
        Team otherTeam = teamRepository.save(Team.builder()
            .workspaceId(otherWorkspaceId).name("Other").colorIndex(1).build());

        mockMvc.perform(delete("/settings/teams/" + otherTeam.getId())
                .session(webSession).with(csrf()))
            .andExpect(status().isNotFound());

        assertThat(teamRepository.findById(otherTeam.getId())).isPresent();
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    void teamEndpointsRedirectToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/settings/teams"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/settings/teams").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(delete("/settings/teams/1").with(csrf()))
            .andExpect(status().is3xxRedirection());
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

    private Long createOtherWorkspaceId() {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(99L).githubLogin("other-owner").lastLoginAt(Instant.now()).build());
        Workspace otherWorkspace = workspaceRepository.save(Workspace.builder()
            .name("Other WS").slug("other-ws").ownerId(otherOwner.getId())
            .plan("FREE").apiToken("other-tok").build());
        return otherWorkspace.getId();
    }
}
