package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.request.SnapshotHistoryRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse;
import io.simakov.analytics.domain.model.enums.TimeGroupBy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotControllerTest extends BaseIT {

    @Test
    void runSnapshotReturns200WithNoUsers() {
        RunSnapshotRequest req = new RunSnapshotRequest(List.of(), List.of(), 30, LocalDate.now());

        ResponseEntity<RunSnapshotResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/snapshots/run",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            RunSnapshotResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void runSnapshotReturns401WithoutToken() {
        RunSnapshotRequest req = new RunSnapshotRequest(List.of(), List.of(), 30, LocalDate.now());

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/snapshots/run",
            HttpMethod.POST,
            new HttpEntity<>(req),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void backfillReturns202() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/snapshots/backfill",
            HttpMethod.POST,
            new HttpEntity<>(null, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void historyReturns200WithEmptyResult() {
        SnapshotHistoryRequest req = new SnapshotHistoryRequest(
            List.of(999L),
            LocalDate.now().minusDays(30),
            LocalDate.now(),
            TimeGroupBy.WEEK);

        ResponseEntity<SnapshotHistoryResponse> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/snapshots/history",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            SnapshotHistoryResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void historyReturns400WhenUserIdsEmpty() {
        SnapshotHistoryRequest req = new SnapshotHistoryRequest(
            List.of(),
            LocalDate.now().minusDays(7),
            LocalDate.now(),
            TimeGroupBy.DAY);

        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/snapshots/history",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
