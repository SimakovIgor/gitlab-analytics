package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.JiraIncident;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DoraControllerTest extends BaseIT {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private GitSourceRepository gitSourceRepository;
    @Autowired
    private TrackedProjectRepository trackedProjectRepository;
    @Autowired
    private MergeRequestRepository mergeRequestRepository;
    @Autowired
    private ReleaseTagRepository releaseTagRepository;
    @Autowired
    private JiraIncidentRepository jiraIncidentRepository;
    private Long projectId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl").baseUrl("https://git.example.com").build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();
    }

    @Test
    void doraPageReturns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void doraPageRedirectsToLoginWithoutAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"))
            .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).contains("login");
    }

    @Test
    void doraPageRendersAllMetricSections() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .contains("Lead Time")
            .contains("Deploy Frequency")
            .contains("Change Failure Rate")
            .contains("MTTR")
            .contains("Скоро"); // MTTR is still COMING_SOON
    }

    @Test
    void doraPageShowsZeroMrsWhenNoData() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(">0<");
    }

    @Test
    void doraPageShowsTotalMrCountWhenDataExists() throws Exception {
        ReleaseTag tag = saveReleaseTag("v1.0.0", now.minus(1, ChronoUnit.DAYS));
        saveMergedMrWithTag(1L, 4, tag.getId());
        saveMergedMrWithTag(2L, 8, tag.getId());

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("2 MR");
    }

    @Test
    void doraPageFiltersToSelectedProjectIds() throws Exception {
        saveMergedMr(1L, 4);

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("projectIds", "99999")
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        // With unknown project filter, no release data → no-data hint is shown
        assertThat(result.getResponse().getContentAsString())
            .contains("sync/releases");
    }

    @Test
    void doraPageExcludesOpenedMrsFromCount() throws Exception {
        saveMergedMr(1L, 4);
        MergeRequest opened = new MergeRequest();
        opened.setTrackedProjectId(projectId);
        opened.setGitlabMrId(2L);
        opened.setGitlabMrIid(2L);
        opened.setState(MrState.OPENED);
        opened.setCreatedAtGitlab(now.minus(2, ChronoUnit.HOURS));
        opened.setAuthorGitlabUserId(1L);
        mergeRequestRepository.save(opened);

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(">1<");
    }

    @Test
    void doraPageShowsProjectCheckboxes() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("repo");
    }

    @Test
    void doraPageDoesNotLeakProjectsFromOtherWorkspace() throws Exception {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(2L).githubLogin("other-owner").lastLoginAt(Instant.now()).build());
        Workspace otherWorkspace = workspaceRepository.save(Workspace.builder()
            .name("Other").slug("other-ws").ownerId(otherOwner.getId()).plan("FREE").apiToken("other-tok").build());
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspace.getId()).name("other-gl").baseUrl("https://other.com").build());
        trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(otherWorkspace.getId()).gitSourceId(otherSource.getId())
            .gitlabProjectId(99L).pathWithNamespace("other/secret-repo").name("secret-repo")
            .tokenEncrypted("tok").enabled(true).build());

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("repo")
            .doesNotContain("secret-repo");
    }

    // ── Change Failure Rate ──────────────────────────────────────────────

    @Test
    void doraPageRendersCfrCardAsAvailable() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        // CFR card should NOT be in the "Скоро" TBD section
        // It should render the real card with incident/deploy data
        assertThat(body).contains("Change Failure Rate");
        assertThat(body).contains("cfrSparkline");
    }

    @Test
    void doraPageShowsCfrNoDataWhenNoDeploysOrIncidents() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Нет данных");
    }

    @Test
    void doraPageShowsCfrPercentWhenDataExists() throws Exception {
        // 4 deploys, 1 incident → 25%
        for (int i = 1; i <= 4; i++) {
            saveReleaseTag("v" + i, now.minus(i, ChronoUnit.DAYS));
        }
        saveIncident("MI-1", now.minus(2, ChronoUnit.DAYS));

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("25.0");
        assertThat(body).contains("1 инцидентов");
        assertThat(body).contains("4 деплоев");
    }

    @Test
    void doraPageRendersCfrChart() throws Exception {
        saveReleaseTag("v1", now.minus(5, ChronoUnit.DAYS));
        saveIncident("MI-1", now.minus(5, ChronoUnit.DAYS));

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("cfrChart");
        assertThat(body).contains("Change Failure Rate · по неделям");
    }

    @Test
    void doraPageSyncIncidentsEndpointTriggersSync() throws Exception {
        MvcResult result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/dora/sync/incidents")
                    .session(webSession)
                    .with(oauth2Login())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("ok");
    }

    private void saveMergedMr(Long gitlabMrId,
                              int leadHours) {
        saveMergedMrWithTag(gitlabMrId, leadHours, null);
    }

    private void saveMergedMrWithTag(Long gitlabMrId,
                                     int leadHours,
                                     Long releaseTagId) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(now.minus(leadHours, ChronoUnit.HOURS));
        mr.setMergedAtGitlab(now.minus(1, ChronoUnit.HOURS));
        mr.setAuthorGitlabUserId(1L);
        mr.setReleaseTagId(releaseTagId);
        mergeRequestRepository.save(mr);
    }

    private ReleaseTag saveReleaseTag(String tagName,
                                      Instant prodDeployedAt) {
        ReleaseTag tag = new ReleaseTag();
        tag.setTrackedProjectId(projectId);
        tag.setTagName(tagName);
        tag.setTagCreatedAt(prodDeployedAt);
        tag.setProdDeployedAt(prodDeployedAt);
        return releaseTagRepository.save(tag);
    }

    private void saveIncident(String jiraKey,
                              Instant createdAt) {
        jiraIncidentRepository.save(JiraIncident.builder()
            .workspaceId(testWorkspaceId)
            .trackedProjectId(projectId)
            .jiraKey(jiraKey)
            .summary("Incident " + jiraKey)
            .createdAt(createdAt)
            .componentName("repo")
            .build());
    }
}
