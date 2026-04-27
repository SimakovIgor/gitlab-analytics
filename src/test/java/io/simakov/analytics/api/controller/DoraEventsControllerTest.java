package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.DoraDeployEvent;
import io.simakov.analytics.domain.model.DoraIncidentEvent;
import io.simakov.analytics.domain.repository.DoraDeployEventRepository;
import io.simakov.analytics.domain.repository.DoraIncidentEventRepository;
import io.simakov.analytics.domain.repository.DoraServiceRepository;
import io.simakov.analytics.dora.model.DeployStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DoraEventsControllerTest extends BaseIT {

    @Autowired
    private DoraDeployEventRepository deployEventRepository;

    @Autowired
    private DoraIncidentEventRepository incidentEventRepository;

    @Autowired
    private DoraServiceRepository doraServiceRepository;

    // ── Deploy ────────────────────────────────────────────────────────────

    @Test
    void recordDeployReturns200AndCreatedStatus() {
        Map<String, Object> body = deployBody("deploy-001", "payments", "production", "SUCCESS");

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "CREATED");
        assertThat(resp.getBody()).containsKey("id");
    }

    @Test
    void recordDeployPersistsEventToDatabase() {
        Map<String, Object> body = deployBody("deploy-persist-001", "order-service", "production", "SUCCESS");

        restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        Optional<DoraDeployEvent> saved =
            deployEventRepository.findByWorkspaceIdAndIdempotencyKey(testWorkspaceId, "deploy-persist-001");
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(DeployStatus.SUCCESS);
        assertThat(saved.get().getEnvironment()).isEqualTo("production");
    }

    @Test
    void recordDeployAutoCreatesDoraService() {
        Map<String, Object> body = deployBody("deploy-svc-001", "brand-new-service", "production", "SUCCESS");

        restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(doraServiceRepository.findByWorkspaceIdAndNameIgnoreCase(testWorkspaceId, "brand-new-service"))
            .isPresent();
    }

    @Test
    void recordDeployIsIdempotent() {
        Map<String, Object> body = deployBody("deploy-idem-001", "payments", "production", "SUCCESS");

        ResponseEntity<Map> first = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(first.getBody()).containsEntry("status", "CREATED");
        assertThat(second.getBody()).containsEntry("status", "DUPLICATE");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(deployEventRepository.count()).isEqualTo(1);
    }

    @Test
    void recordDeployReturns401WithoutToken() {
        Map<String, Object> body = deployBody("deploy-401", "payments", "production", "SUCCESS");

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void recordDeployReturns400WhenRequiredFieldsMissing() {
        // Missing idempotencyKey, status, deployedAt
        Map<String, Object> body = Map.of("service", "payments", "environment", "production");

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void recordDeployFailedStatusIsPersisted() {
        Map<String, Object> body = deployBody("deploy-failed-001", "payments", "production", "FAILED");

        restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/deploy",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        Optional<DoraDeployEvent> saved =
            deployEventRepository.findByWorkspaceIdAndIdempotencyKey(testWorkspaceId, "deploy-failed-001");
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(DeployStatus.FAILED);
    }

    // ── Incident ──────────────────────────────────────────────────────────

    @Test
    void recordIncidentReturns200AndCreatedStatus() {
        Map<String, Object> body = incidentBody("inc-001", "payments",
            "2026-04-27T10:00:00Z", "2026-04-27T12:00:00Z");

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "CREATED");
    }

    @Test
    void recordIncidentPersistsEventToDatabase() {
        Map<String, Object> body = incidentBody("inc-persist-001", "payments",
            "2026-04-27T10:00:00Z", "2026-04-27T12:30:00Z");

        restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        Optional<DoraIncidentEvent> saved =
            incidentEventRepository.findByWorkspaceIdAndIdempotencyKey(testWorkspaceId, "inc-persist-001");
        assertThat(saved).isPresent();
        assertThat(saved.get().getResolvedAt()).isNotNull();
    }

    @Test
    void recordIncidentIsIdempotent() {
        Map<String, Object> body = incidentBody("inc-idem-001", "payments",
            "2026-04-27T10:00:00Z", null);

        ResponseEntity<Map> first = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(first.getBody()).containsEntry("status", "CREATED");
        assertThat(second.getBody()).containsEntry("status", "DUPLICATE");
        assertThat(incidentEventRepository.count()).isEqualTo(1);
    }

    @Test
    void recordOpenIncidentWithoutResolvedAt() {
        Map<String, Object> body = incidentBody("inc-open-001", "payments",
            "2026-04-27T10:00:00Z", null);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Optional<DoraIncidentEvent> saved =
            incidentEventRepository.findByWorkspaceIdAndIdempotencyKey(testWorkspaceId, "inc-open-001");
        assertThat(saved).isPresent();
        assertThat(saved.get().getResolvedAt()).isNull();
    }

    @Test
    void recordIncidentReturns401WithoutToken() {
        Map<String, Object> body = incidentBody("inc-401", "payments",
            "2026-04-27T10:00:00Z", null);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void recordIncidentReturns400WhenRequiredFieldsMissing() {
        // Missing startedAt
        Map<String, Object> body = Map.of(
            "idempotencyKey", "inc-400",
            "service", "payments");

        ResponseEntity<Map> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/dora/events/incident",
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> deployBody(String idempotencyKey,
                                            String service,
                                            String environment,
                                            String status) {
        return Map.of(
            "idempotencyKey", idempotencyKey,
            "service", service,
            "environment", environment,
            "deployedAt", "2026-04-27T14:00:00Z",
            "status", status
        );
    }

    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    private Map<String, Object> incidentBody(String idempotencyKey,
                                              String service,
                                              String startedAt,
                                              String resolvedAt) {
        if (resolvedAt == null) {
            return Map.of(
                "idempotencyKey", idempotencyKey,
                "service", service,
                "startedAt", startedAt
            );
        }
        return Map.of(
            "idempotencyKey", idempotencyKey,
            "service", service,
            "startedAt", startedAt,
            "resolvedAt", resolvedAt
        );
    }
}
