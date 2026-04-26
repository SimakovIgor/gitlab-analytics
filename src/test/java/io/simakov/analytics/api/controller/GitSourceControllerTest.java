package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.response.GitSourceResponse;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitSourceControllerTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Test
    void createSourceReturns201AndPersists() {
        Map<String, String> body = Map.of("name", "prod-gl", "baseUrl", "https://git.example.com");

        ResponseEntity<GitSourceResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sources/gitlab",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            GitSourceResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().name()).isEqualTo("prod-gl");
        assertThat(gitSourceRepository.findAll()).hasSize(1);
    }

    @Test
    void createSourceReturns401WithoutToken() {
        Map<String, String> body = Map.of("name", "gl", "baseUrl", "https://git.example.com");

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sources/gitlab",
            HttpMethod.POST,
            new HttpEntity<>(body),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createSourceReturns400WhenNameBlank() {
        Map<String, String> body = Map.of("name", "", "baseUrl", "https://git.example.com");

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sources/gitlab",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
