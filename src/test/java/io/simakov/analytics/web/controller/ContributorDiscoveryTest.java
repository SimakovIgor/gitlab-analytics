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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
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
                .with(user("owner@test.com").roles("USER")))
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
                .with(user("owner@test.com").roles("USER")))
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
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"alreadyTracked\":true");
    }

    @Test
    void marksBotFromCommitWithSuspectedBotFlag() throws Exception {
        MergeRequest mr = saveMergedMr(10L);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("bot001")
            .authorEmail("noreply@gitlab.com")
            .authorName("GitLab Bot")
            .authoredDate(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("noreply@gitlab.com");
        assertThat(body).contains("\"suspectedBot\":true");
    }

    @Test
    void humanContributorHasSuspectedBotFalse() throws Exception {
        MergeRequest mr = saveMergedMr(11L);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("human01")
            .authorEmail("alice@company.com")
            .authorName("Alice Smith")
            .authoredDate(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("alice@company.com");
        assertThat(body).contains("\"suspectedBot\":false");
    }

    @Test
    void includesMrAuthorPlaceholderAsBot() throws Exception {
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(100L)
            .gitlabMrIid(100L)
            .state(MrState.MERGED)
            .authorGitlabUserId(9001L)
            .authorName("Placeholder github Source User")
            .authorUsername("user_placeholder_abc123")
            .createdAtGitlab(Instant.now())
            .mergedAtGitlab(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Placeholder github Source User");
        assertThat(body).contains("\"suspectedBot\":true");
        assertThat(body).contains("\"alreadyTracked\":false");
    }

    @Test
    void includesMrAuthorHumanNotFoundInCommits() throws Exception {
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(200L)
            .gitlabMrIid(200L)
            .state(MrState.MERGED)
            .authorGitlabUserId(9002L)
            .authorName("Charlie Developer")
            .authorUsername("c.developer")
            .createdAtGitlab(Instant.now())
            .mergedAtGitlab(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Charlie Developer");
        assertThat(body).contains("\"suspectedBot\":false");
        assertThat(body).contains("\"mrCount\":1");
    }

    @Test
    void mrAuthorMarkedAsTrackedWhenAliasExists() throws Exception {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Dave")
            .enabled(true)
            .build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(9003L)
            .username("d.dave")
            .build());

        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(300L)
            .gitlabMrIid(300L)
            .state(MrState.MERGED)
            .authorGitlabUserId(9003L)
            .authorName("Dave")
            .authorUsername("d.dave")
            .createdAtGitlab(Instant.now())
            .mergedAtGitlab(Instant.now())
            .build());

        MvcResult result = mockMvc.perform(get("/settings/users/discovered")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"alreadyTracked\":true");
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
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"alreadyTracked\":true");
    }
}
