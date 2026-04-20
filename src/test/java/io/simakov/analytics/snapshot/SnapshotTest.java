package io.simakov.analytics.snapshot;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.request.SnapshotHistoryRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.model.enums.TimeGroupBy;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotTest extends BaseIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 17);
    private static final String METRICS_JSON = "{\"mr_merged_count\":5,\"lines_added\":100.0}";

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private GitSourceRepository gitSourceRepository;
    @Autowired
    private TrackedProjectRepository trackedProjectRepository;
    @Autowired
    private TrackedUserRepository trackedUserRepository;
    @Autowired
    private TrackedUserAliasRepository aliasRepository;
    @Autowired
    private MetricSnapshotRepository snapshotRepository;

    private Long projectId;
    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("gl").baseUrl("https://git.test").build());
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId()).gitlabProjectId(1L)
            .pathWithNamespace("team/repo").name("repo").tokenEncrypted("tok").enabled(true).build());
        projectId = project.getId();

        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice").email("alice@example.com").enabled(true).build());
        aliceId = alice.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId).gitlabUserId(100L).email("alice@example.com").build());

        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Bob").email("bob@example.com").enabled(true).build());
        bobId = bob.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bobId).gitlabUserId(200L).email("bob@example.com").build());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/snapshots/run
    // -------------------------------------------------------------------------

    @Test
    void runSnapshotCreatesOneRowPerUser() {
        RunSnapshotRequest req = new RunSnapshotRequest(List.of(aliceId, bobId), List.of(projectId), 30, TODAY);

        ResponseEntity<RunSnapshotResponse> resp = post("/api/v1/snapshots/run", req, RunSnapshotResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().snapshotsCreated()).isEqualTo(2);
        assertThat(resp.getBody().snapshotDate()).isEqualTo(TODAY);
        assertThat(snapshotRepository.findAll()).hasSize(2);
    }

    @Test
    void runSnapshotIsIdempotentForSameDate() {
        RunSnapshotRequest req = new RunSnapshotRequest(List.of(aliceId), List.of(projectId), 30, TODAY);

        post("/api/v1/snapshots/run", req, RunSnapshotResponse.class);
        post("/api/v1/snapshots/run", req, RunSnapshotResponse.class);

        // Second run updates existing row — still 1 snapshot for Alice on TODAY
        assertThat(snapshotRepository.findAll()).hasSize(1);
    }

    @Test
    void runSnapshotWithEmptyBodyUsesDefaults() {
        // Empty body (all nulls) → service falls back to all enabled users / projects / defaults
        RunSnapshotRequest emptyReq = new RunSnapshotRequest(null, null, null, null);
        ResponseEntity<RunSnapshotResponse> resp = post("/api/v1/snapshots/run", emptyReq, RunSnapshotResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Both Alice and Bob are enabled — 2 snapshots expected
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().snapshotsCreated()).isEqualTo(2);
    }

    @Test
    void runSnapshotPersistsWindowDays() {
        RunSnapshotRequest req = new RunSnapshotRequest(List.of(aliceId), List.of(projectId), 90, TODAY);

        post("/api/v1/snapshots/run", req, RunSnapshotResponse.class);

        MetricSnapshot saved = snapshotRepository
            .findByWorkspaceIdAndSnapshotDateAndTrackedUserIdIn(testWorkspaceId, TODAY, List.of(aliceId))
            .stream().findFirst().orElseThrow();
        assertThat(saved.getWindowDays()).isEqualTo(90);
        assertThat(saved.getSnapshotDate()).isEqualTo(TODAY);
        assertThat(saved.getMetricsJson()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/snapshots/history — grouping
    // -------------------------------------------------------------------------

    @Test
    void historyGroupByDayReturnsOnePointPerDay() {
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 1));
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 2));
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 3));

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3), TimeGroupBy.DAY);

        assertThat(resp.points()).hasSize(3);
        assertThat(resp.points()).extracting(SnapshotHistoryResponse.SnapshotPoint::periodLabel)
            .containsExactly("2026-04-01", "2026-04-02", "2026-04-03");
    }

    @Test
    void historyGroupByWeekMergesMultipleDaysInSameWeek() {
        // ISO week 2026-W15: Mon Apr 6 – Sun Apr 12
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 6));
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 8));
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 10));

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), TimeGroupBy.WEEK);

        assertThat(resp.points()).hasSize(1);
        assertThat(resp.points().get(0).periodLabel()).isEqualTo("2026-W15");
    }

    @Test
    void historyGroupByWeekSplitsDifferentWeeks() {
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 6));   // W15
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 13));  // W16

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), TimeGroupBy.WEEK);

        assertThat(resp.points()).hasSize(2);
        assertThat(resp.points()).extracting(SnapshotHistoryResponse.SnapshotPoint::periodLabel)
            .containsExactly("2026-W15", "2026-W16");
    }

    @Test
    void historyGroupByMonthMergesMultipleDaysInSameMonth() {
        saveSnapshot(aliceId, LocalDate.of(2026, 3, 1));
        saveSnapshot(aliceId, LocalDate.of(2026, 3, 15));
        saveSnapshot(aliceId, LocalDate.of(2026, 3, 31));

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), TimeGroupBy.MONTH);

        assertThat(resp.points()).hasSize(1);
        assertThat(resp.points().get(0).periodLabel()).isEqualTo("2026-03");
    }

    @Test
    void historyGroupByMonthSplitsDifferentMonths() {
        saveSnapshot(aliceId, LocalDate.of(2026, 2, 28));
        saveSnapshot(aliceId, LocalDate.of(2026, 3, 1));

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31), TimeGroupBy.MONTH);

        assertThat(resp.points()).hasSize(2);
        assertThat(resp.points()).extracting(SnapshotHistoryResponse.SnapshotPoint::periodLabel)
            .containsExactly("2026-02", "2026-03");
    }

    @Test
    void historyRespectsDateRange() {
        saveSnapshot(aliceId, LocalDate.of(2026, 1, 31)); // outside range
        saveSnapshot(aliceId, LocalDate.of(2026, 2, 15)); // inside
        saveSnapshot(aliceId, LocalDate.of(2026, 3, 1));  // outside range

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), TimeGroupBy.DAY);

        assertThat(resp.points()).hasSize(1);
        assertThat(resp.points().get(0).periodLabel()).isEqualTo("2026-02-15");
    }

    @Test
    void historyReturnsOnlyRequestedUsers() {
        saveSnapshot(aliceId, TODAY);
        saveSnapshot(bobId, TODAY);

        SnapshotHistoryResponse resp = history(List.of(aliceId), TODAY, TODAY, TimeGroupBy.DAY);

        assertThat(resp.points()).hasSize(1);
        List<SnapshotHistoryResponse.UserSnapshotMetrics> users = resp.points().get(0).users();
        assertThat(users).hasSize(1);
        assertThat(users.get(0).userId()).isEqualTo(aliceId);
    }

    @Test
    void historyGroupByWeekUsesLatestSnapshotInGroup() {
        // Two snapshots for Alice in the same week (W15: Apr 6 – Apr 12)
        // Apr 10 has mr_merged_count = 99 — that one must win
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 6), "{\"mr_merged_count\":1}");
        saveSnapshot(aliceId, LocalDate.of(2026, 4, 10), "{\"mr_merged_count\":99}");

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), TimeGroupBy.WEEK);

        assertThat(resp.points()).hasSize(1);
        SnapshotHistoryResponse.UserSnapshotMetrics user = resp.points().get(0).users().get(0);
        assertThat(user.snapshotDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(((Number) user.metrics().get("mr_merged_count")).intValue()).isEqualTo(99);
    }

    @Test
    void historyReturnsEmptyWhenNoSnapshotsInRange() {
        saveSnapshot(aliceId, LocalDate.of(2026, 1, 1));

        SnapshotHistoryResponse resp = history(List.of(aliceId),
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), TimeGroupBy.MONTH);

        assertThat(resp.points()).isEmpty();
        assertThat(resp.groupBy()).isEqualTo("MONTH");
    }

    @Test
    void historyDoesNotLeakSnapshotsFromOtherWorkspace() {
        AppUser otherOwner = appUserRepository.save(AppUser.builder()
            .githubId(99L).githubLogin("other-owner").lastLoginAt(java.time.Instant.now()).build());
        Workspace otherWorkspace = workspaceRepository.save(Workspace.builder()
            .name("Other WS").slug("other-ws").ownerId(otherOwner.getId()).plan("FREE").apiToken("other-tok").build());
        TrackedUser otherUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(otherWorkspace.getId()).displayName("Other User")
            .email("other@example.com").enabled(true).build());

        snapshotRepository.save(MetricSnapshot.builder()
            .workspaceId(otherWorkspace.getId())
            .trackedUserId(otherUser.getId())
            .snapshotDate(TODAY)
            .dateFrom(TODAY.minusDays(30).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
            .dateTo(TODAY.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
            .windowDays(30)
            .periodType(PeriodType.CUSTOM)
            .scopeType(ScopeType.USER)
            .metricsJson(METRICS_JSON)
            .build());

        SnapshotHistoryResponse resp = history(List.of(otherUser.getId()), TODAY, TODAY, TimeGroupBy.DAY);

        assertThat(resp.points()).isEmpty();
    }

    @Test
    void historyContainsMultipleUsersInSamePoint() {
        saveSnapshot(aliceId, TODAY);
        saveSnapshot(bobId, TODAY);

        SnapshotHistoryResponse resp = history(List.of(aliceId, bobId), TODAY, TODAY, TimeGroupBy.DAY);

        assertThat(resp.points()).hasSize(1);
        assertThat(resp.points().get(0).users()).hasSize(2);
        assertThat(resp.points().get(0).users())
            .extracting(SnapshotHistoryResponse.UserSnapshotMetrics::userId)
            .containsExactlyInAnyOrder(aliceId, bobId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void saveSnapshot(Long userId,
                              LocalDate date) {
        saveSnapshot(userId, date, METRICS_JSON);
    }

    private void saveSnapshot(Long userId,
                              LocalDate date,
                              String metricsJson) {
        snapshotRepository.save(MetricSnapshot.builder()
            .workspaceId(testWorkspaceId)
            .trackedUserId(userId)
            .snapshotDate(date)
            .dateFrom(date.minusDays(30).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
            .dateTo(date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
            .windowDays(30)
            .periodType(PeriodType.CUSTOM)
            .scopeType(ScopeType.USER)
            .metricsJson(metricsJson)
            .build());
    }

    private SnapshotHistoryResponse history(List<Long> userIds,
                                            LocalDate from,
                                            LocalDate to,
                                            TimeGroupBy groupBy) {
        SnapshotHistoryRequest req = new SnapshotHistoryRequest(userIds, from, to, groupBy);
        ResponseEntity<SnapshotHistoryResponse> resp = post(
            "/api/v1/snapshots/history", req, SnapshotHistoryResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody();
    }

    private <T> ResponseEntity<T> post(String path,
                                       Object body,
                                       Class<T> responseType) {
        return restTemplate.exchange(
            "http://localhost:" + port + path,
            HttpMethod.POST,
            new HttpEntity<>(body, authHeaders()),
            responseType);
    }

}
