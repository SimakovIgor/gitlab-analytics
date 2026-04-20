package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
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
    void doraPageRendersLeadTimeSectionAndTbdSections() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .contains("Lead Time")
            .contains("Deployment Frequency")
            .contains("Change Failure Rate")
            .contains("Mean Time to Restore")
            .contains("Скоро");
    }

    @Test
    void doraPageShowsZeroMrsWhenNoData() throws Exception {
        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("days", "30"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(">0<");
    }

    @Test
    void doraPageShowsTotalMrCountWhenDataExists() throws Exception {
        saveMergedMr(1L, 4);
        saveMergedMr(2L, 8);

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("days", "30"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(">2<");
    }

    @Test
    void doraPageFiltersToSelectedProjectIds() throws Exception {
        saveMergedMr(1L, 4);

        MvcResult result = mockMvc.perform(get("/dora").session(webSession).with(oauth2Login())
                .param("projectIds", "99999")
                .param("days", "30"))
            .andExpect(status().isOk())
            .andReturn();

        // With unknown project filter, count should be 0 not 1
        assertThat(result.getResponse().getContentAsString())
            .contains(">0<")
            .doesNotContain(">1<");
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
                .param("days", "30"))
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

    private void saveMergedMr(Long gitlabMrId,
                              int leadHours) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(now.minus(leadHours, ChronoUnit.HOURS));
        mr.setMergedAtGitlab(now);
        mr.setAuthorGitlabUserId(1L);
        mergeRequestRepository.save(mr);
    }
}
