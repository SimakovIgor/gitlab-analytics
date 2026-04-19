package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SuppressWarnings({"PMD.JUnitTestContainsTooManyAsserts", "checkstyle:ClassDataAbstractionCoupling"})
class ContributorDiscoveryTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private MergeRequestRepository mergeRequestRepository;

    @Autowired
    private MergeRequestCommitRepository commitRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    private Long projectId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("gl")
            .baseUrl("https://gitlab.example.com")
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

        projectId = project.getId();
    }

    private MergeRequest saveMergedMr(long gitlabMrId) {
        return mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(gitlabMrId)
            .gitlabMrIid(gitlabMrId)
            .state(MrState.MERGED)
            .createdAtGitlab(Instant.now())
            .mergedAtGitlab(Instant.now())
            .build());
    }

    @Test
    void returnsEmptyListWhenNoCommits() throws Exception {
        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void returnsContributorFromCommitData() throws Exception {
        MergeRequest mr = saveMergedMr(1L);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("abc123")
            .authorEmail("alice@example.com")
            .authorName("Alice Smith")
            .authoredDate(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("alice@example.com");
        assertThat(body).contains("Alice Smith");
        assertThat(body).contains("\"alreadyTracked\":false");
    }

    @Test
    void marksContributorAsTrackedWhenEmailMatchesUser() throws Exception {
        trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build());

        MergeRequest mr = saveMergedMr(2L);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("abc456")
            .authorEmail("alice@example.com")
            .authorName("Alice Smith")
            .authoredDate(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"alreadyTracked\":true");
    }

    @Test
    void marksContributorAsTrackedWhenEmailMatchesAlias() throws Exception {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Bob")
            .email("bob@company.com")
            .enabled(true)
            .build());

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .email("bob.smith@example.com")
            .build());

        MergeRequest mr = saveMergedMr(3L);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("def789")
            .authorEmail("bob.smith@example.com")
            .authorName("Bob Smith")
            .authoredDate(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(oauth2Login()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"alreadyTracked\":true");
    }
}
