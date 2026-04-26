package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.UpdateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.TrackedUserResponse;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedUserControllerTest extends BaseIT {

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build());
        userId = user.getId();
    }

    @Test
    void patchDisablesUser() {
        UpdateTrackedUserRequest req = new UpdateTrackedUserRequest(null, null, false);

        ResponseEntity<TrackedUserResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users/" + userId,
            HttpMethod.PATCH,
            new HttpEntity<>(req, authHeaders()),
            TrackedUserResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().enabled()).isFalse();
        assertThat(trackedUserRepository.findById(userId)).isPresent()
            .get().extracting(TrackedUser::isEnabled).isEqualTo(false);
    }

    @Test
    void patchUpdatesDisplayNameAndEmail() {
        UpdateTrackedUserRequest req = new UpdateTrackedUserRequest("Alice B.", "alice.b@example.com", null);

        ResponseEntity<TrackedUserResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users/" + userId,
            HttpMethod.PATCH,
            new HttpEntity<>(req, authHeaders()),
            TrackedUserResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().displayName()).isEqualTo("Alice B.");
        assertThat(resp.getBody().email()).isEqualTo("alice.b@example.com");
        assertThat(resp.getBody().enabled()).isTrue();
    }

    @Test
    void patchIgnoresNullFields() {
        UpdateTrackedUserRequest req = new UpdateTrackedUserRequest(null, null, null);

        ResponseEntity<TrackedUserResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users/" + userId,
            HttpMethod.PATCH,
            new HttpEntity<>(req, authHeaders()),
            TrackedUserResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().displayName()).isEqualTo("Alice");
        assertThat(resp.getBody().email()).isEqualTo("alice@example.com");
        assertThat(resp.getBody().enabled()).isTrue();
    }

    @Test
    void patchReturns404ForUnknownUser() {
        UpdateTrackedUserRequest req = new UpdateTrackedUserRequest(null, null, false);

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users/99999",
            HttpMethod.PATCH,
            new HttpEntity<>(req, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createUserReturns201AndPersists() {
        CreateTrackedUserRequest req = new CreateTrackedUserRequest("Bob", "bob@example.com", List.of());

        ResponseEntity<TrackedUserResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            TrackedUserResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().displayName()).isEqualTo("Bob");
        assertThat(trackedUserRepository.findAll()).hasSize(2); // Alice from setUp + Bob
    }

    @Test
    void listUsersReturnsAllForWorkspace() {
        ResponseEntity<List<TrackedUserResponse>> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/users",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            new ParameterizedTypeReference<>() { });

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).displayName()).isEqualTo("Alice");
    }
}
