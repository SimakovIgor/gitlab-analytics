package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AiInsightRecord;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.AiInsightCacheRepository;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.insights.ai.AiInsightDto;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiInsightControllerTest extends BaseIT {

    @Autowired
    private AiInsightCacheRepository cacheRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    // ── GET /insights — page loads ───────────────────────────────────────────

    @Test
    void insightsPage_loadsSuccessfullyWithNoAiCache() throws Exception {
        MvcResult result = mockMvc.perform(get("/insights")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("AI-рекомендации");
    }

    @Test
    void insightsPage_showsCachedAiInsightsFromDb() throws Exception {
        List<AiInsightDto> cached = List.of(
            new AiInsightDto("warn", "Тестовый AI инсайт", "Описание тестового инсайта.")
        );
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", cached, Instant.now().minusSeconds(60));

        MvcResult result = mockMvc.perform(get("/insights?period=LAST_30_DAYS")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Тестовый AI инсайт");
        assertThat(html).contains("Описание тестового инсайта.");
        assertThat(html).contains("insight-row-ai");
    }

    @Test
    void insightsPage_doesNotShowStaleCachedAiInsights() throws Exception {
        List<AiInsightDto> cached = List.of(
            new AiInsightDto("bad", "Устаревший инсайт", "Этот инсайт не должен показываться.")
        );
        // 25 hours old — stale
        saveCache(testWorkspaceId, "LAST_30_DAYS", "all", cached,
            Instant.now().minus(25, ChronoUnit.HOURS));

        MvcResult result = mockMvc.perform(get("/insights?period=LAST_30_DAYS")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain("Устаревший инсайт");
    }

    @Test
    void insightsPage_doesNotLeakAiInsightsFromOtherWorkspace() throws Exception {
        Long otherWsId = createOtherWorkspace();
        List<AiInsightDto> otherInsights = List.of(
            new AiInsightDto("bad", "Чужой инсайт", "Не должен быть виден.")
        );
        saveCache(otherWsId, "LAST_30_DAYS", "all", otherInsights, Instant.now());

        MvcResult result = mockMvc.perform(get("/insights?period=LAST_30_DAYS")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain("Чужой инсайт");
    }

    @Test
    void insightsPage_redirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/insights"))
            .andExpect(status().is3xxRedirection());
    }

    // ── POST /insights/ai/refresh ────────────────────────────────────────────

    @Test
    void refreshAiInsights_requires403ForNonOwner() throws Exception {
        // Create a member user (not owner)
        AppUser member = appUserRepository.save(AppUser.builder()
            .email("member@test.com")
            .name("Member User")
            .lastLoginAt(Instant.now())
            .build());
        workspaceMemberRepository.save(WorkspaceMember.builder()
            .workspaceId(testWorkspaceId)
            .appUserId(member.getId())
            .role("MEMBER")
            .build());
        MockHttpSession memberSession = new MockHttpSession();
        memberSession.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, testWorkspaceId);

        mockMvc.perform(post("/insights/ai/refresh")
                .session(memberSession)
                .with(user(new AppUserPrincipal(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    void refreshAiInsights_redirectsWhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/insights/ai/refresh").with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void refreshAiInsights_returnsCountZeroWhenAiDisabled() throws Exception {
        // api-key is blank in test profile → disabled → returns count=0
        mockMvc.perform(post("/insights/ai/refresh?period=LAST_30_DAYS")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void refreshAiInsights_doesNotWriteToCacheWhenDisabled() throws Exception {
        mockMvc.perform(post("/insights/ai/refresh?period=LAST_30_DAYS")
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf()))
            .andExpect(status().isOk());

        assertThat(cacheRepository.findAll()).isEmpty();
    }

    @Test
    void refreshAiInsights_worksWithProjectIdFilter() throws Exception {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl")
            .baseUrl("https://git.example.com")
            .build());
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        mockMvc.perform(post("/insights/ai/refresh?period=LAST_30_DAYS&projectIds=" + project.getId())
                .session(webSession)
                .with(ownerPrincipal())
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0));

        // With disabled AI: no cache entry created
        assertThat(cacheRepository.findAll()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void saveCache(Long workspaceId, String period, String hash,
                           List<AiInsightDto> insights,
                           Instant generatedAt) throws Exception {
        String json = objectMapper.writeValueAsString(insights);
        cacheRepository.save(AiInsightRecord.builder()
            .workspaceId(workspaceId)
            .period(period)
            .projectIdsHash(hash)
            .insightsJson(json)
            .tokensUsed(100)
            .generatedAt(generatedAt)
            .build());
    }

    private Long createOtherWorkspace() {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .email("other-owner@test.com")
            .name("Other Owner")
            .lastLoginAt(Instant.now())
            .build());
        Workspace ws = workspaceRepository.save(Workspace.builder()
            .name("Other WS")
            .slug("other-ws")
            .ownerId(otherOwner.getId())
            .plan("FREE")
            .apiToken("other-token-123")
            .build());
        return ws.getId();
    }
}
