package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DigestScheduleControllerTest extends BaseIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    private AppUser memberUser;

    // ── GET /settings/digest/status ──────────────────────────────────────────

    @Test
    void statusReturnsDefaultsForNewWorkspace() throws Exception {
        mockMvc.perform(get("/settings/digest/status")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestEnabled").value(true))
            .andExpect(jsonPath("$.digestDay").value("mon"))
            .andExpect(jsonPath("$.digestHour").value(9));
    }

    @Test
    void statusReflectsUpdatedSchedule() throws Exception {
        postSchedule("fri", 17);

        mockMvc.perform(get("/settings/digest/status")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestDay").value("fri"))
            .andExpect(jsonPath("$.digestHour").value(17));
    }

    // ── POST /settings/digest/toggle ─────────────────────────────────────────

    @Test
    void toggleDisablesDigest() throws Exception {
        mockMvc.perform(post("/settings/digest/toggle")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestEnabled").value(false));

        Workspace saved = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        assertThat(saved.isDigestEnabled()).isFalse();
    }

    @Test
    void toggleReEnablesDigest() throws Exception {
        postToggle();  // disable
        mockMvc.perform(post("/settings/digest/toggle")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestEnabled").value(true));
    }

    @Test
    void toggleRequiresOwnerRole() throws Exception {
        mockMvc.perform(post("/settings/digest/toggle")
                .session(memberSession())
                .with(memberPrincipal())
                .with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void toggleRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/settings/digest/toggle").with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    // ── POST /settings/digest/schedule ───────────────────────────────────────

    @Test
    void scheduleUpdatePersistsDayAndHour() throws Exception {
        mockMvc.perform(post("/settings/digest/schedule")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("day", "wed", "hour", 10))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestDay").value("wed"))
            .andExpect(jsonPath("$.digestHour").value(10));

        Workspace saved = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        assertThat(saved.getDigestDay()).isEqualTo("WED");
        assertThat(saved.getDigestHour()).isEqualTo(10);
    }

    @Test
    void scheduleInputIsCaseInsensitive() throws Exception {
        mockMvc.perform(post("/settings/digest/schedule")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("day", "THU", "hour", 8))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digestDay").value("thu"));

        Workspace saved = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        assertThat(saved.getDigestDay()).isEqualTo("THU");
    }

    @Test
    void scheduleRequiresOwnerRole() throws Exception {
        mockMvc.perform(post("/settings/digest/schedule")
                .session(memberSession())
                .with(memberPrincipal())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("day", "mon", "hour", 9))))
            .andExpect(status().isForbidden());

        // DB unchanged
        Workspace unchanged = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        assertThat(unchanged.getDigestDay()).isEqualTo("MON");
        assertThat(unchanged.getDigestHour()).isEqualTo(9);
    }

    @Test
    void scheduleRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/settings/digest/schedule")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("day", "mon", "hour", 9))))
            .andExpect(status().is3xxRedirection());
    }

    // ── Scheduler repository query ────────────────────────────────────────────

    @Test
    void schedulerQueryFindsWorkspaceWithMatchingSchedule() {
        setScheduleInDb("WED", 10);

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, "WED", 10);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getId()).isEqualTo(testWorkspaceId);
    }

    @Test
    void schedulerQueryIgnoresWrongDay() {
        setScheduleInDb("WED", 10);

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, "MON", 10);

        assertThat(due).isEmpty();
    }

    @Test
    void schedulerQueryIgnoresWrongHour() {
        setScheduleInDb("WED", 10);

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, "WED", 11);

        assertThat(due).isEmpty();
    }

    @Test
    void schedulerQueryIgnoresDisabledWorkspace() {
        setScheduleInDb("MON", 9);
        Workspace ws = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        ws.setDigestEnabled(false);
        workspaceRepository.save(ws);

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, "MON", 9);

        assertThat(due).isEmpty();
    }

    @Test
    void schedulerQueryOnlyReturnsMatchingWorkspace() {
        // workspace with FRI/17 — should be found
        setScheduleInDb("FRI", 17);

        // second workspace with TUE/9 — should not be found
        AppUser other = appUserRepository.save(AppUser.builder()
            .email("other@test.com").name("Other").lastLoginAt(Instant.now()).build());
        Workspace otherWs = workspaceRepository.save(Workspace.builder()
            .name("Other WS").slug("other-ws").ownerId(other.getId())
            .plan("FREE").apiToken("other-tok")
            .digestDay("TUE").digestHour(9)
            .build());

        List<Workspace> due = workspaceRepository
            .findAllByDigestEnabledAndDigestDayAndDigestHour(true, "FRI", 17);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getId()).isEqualTo(testWorkspaceId);
        assertThat(due.get(0).getId()).isNotEqualTo(otherWs.getId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private void postToggle() throws Exception {
        mockMvc.perform(post("/settings/digest/toggle")
            .session(webSession).with(ownerPrincipal()).with(csrf()));
    }

    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private void postSchedule(String day, int hour) throws Exception {
        mockMvc.perform(post("/settings/digest/schedule")
            .session(webSession)
            .with(ownerPrincipal())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("day", day, "hour", hour))));
    }

    private void setScheduleInDb(String day, int hour) {
        Workspace ws = workspaceRepository.findById(testWorkspaceId).orElseThrow();
        ws.setDigestDay(day);
        ws.setDigestHour(hour);
        workspaceRepository.save(ws);
    }

    private MockHttpSession memberSession() {
        if (memberUser == null) {
            memberUser = appUserRepository.save(AppUser.builder()
                .email("member@test.com").name("Member").lastLoginAt(Instant.now()).build());
            workspaceMemberRepository.save(WorkspaceMember.builder()
                .workspaceId(testWorkspaceId).appUserId(memberUser.getId()).role("MEMBER").build());
        }
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, testWorkspaceId);
        return session;
    }

    private RequestPostProcessor memberPrincipal() {
        if (memberUser == null) {
            memberSession(); // ensure created
        }
        return SecurityMockMvcRequestPostProcessors.user(new AppUserPrincipal(memberUser));
    }
}
