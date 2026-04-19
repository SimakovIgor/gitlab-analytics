package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.response.TrackedProjectResponse;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
class TrackedProjectControllerTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private GitSource ownSource;

    @BeforeEach
    void setUp() {
        ownSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("own-gl")
            .baseUrl("https://git.example.com")
            .build());
    }

    @Test
    void createProjectReturns201ForOwnGitSource() {
        CreateTrackedProjectRequest req = new CreateTrackedProjectRequest(
            ownSource.getId(), 42L, "org/repo", "repo", "glpat-test"
        );

        ResponseEntity<TrackedProjectResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/projects",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            TrackedProjectResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(trackedProjectRepository.findAll()).hasSize(1);
    }

    @Test
    void createProjectReturns404WhenGitSourceBelongsToAnotherWorkspace() {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(99L).githubLogin("other-owner").lastLoginAt(Instant.now()).build());
        Workspace otherWorkspace = workspaceRepository.save(Workspace.builder()
            .name("Other WS").slug("other-ws").ownerId(otherOwner.getId()).plan("FREE").apiToken("other-tok").build());
        GitSource otherSource = gitSourceRepository.save(GitSource.builder()
            .workspaceId(otherWorkspace.getId()).name("other-gl").baseUrl("https://other.com").build());

        CreateTrackedProjectRequest req = new CreateTrackedProjectRequest(
            otherSource.getId(), 99L, "other/repo", "repo", "glpat-test"
        );

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/projects",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(trackedProjectRepository.findAll()).isEmpty();
    }
}
