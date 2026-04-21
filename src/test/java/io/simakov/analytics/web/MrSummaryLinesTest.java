package io.simakov.analytics.web;

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
import io.simakov.analytics.web.dto.MrSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MrSummaryLinesTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;
    @Autowired
    private TrackedProjectRepository trackedProjectRepository;
    @Autowired
    private TrackedUserRepository trackedUserRepository;
    @Autowired
    private TrackedUserAliasRepository aliasRepository;
    @Autowired
    private MergeRequestRepository mergeRequestRepository;
    @Autowired
    private MergeRequestCommitRepository commitRepository;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId).name("gl").baseUrl("https://git.test").build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId).gitSourceId(source.getId())
            .gitlabProjectId(1L).pathWithNamespace("org/repo").name("repo")
            .tokenEncrypted("tok").enabled(true).build());

        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId).displayName("Alice")
            .email("alice@example.com").enabled(true).build());
        aliceId = alice.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId).gitlabUserId(100L).email("alice@example.com").build());

        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId).displayName("Bob")
            .email("bob@example.com").enabled(true).build());
        bobId = bob.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bobId).gitlabUserId(200L).email("bob@example.com").build());

        // MR1: Alice authored, 2 commits by Alice (+80/-20) and 1 commit by Bob (+100/-50)
        MergeRequest mr1 = mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(project.getId()).gitlabMrId(1L).gitlabMrIid(1L)
            .authorGitlabUserId(100L).state(MrState.MERGED)
            .createdAtGitlab(Instant.parse("2026-04-01T10:00:00Z"))
            .mergedAtGitlab(Instant.parse("2026-04-01T12:00:00Z"))
            .build());

        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr1.getId()).gitlabCommitSha("sha1")
            .authorEmail("alice@example.com").additions(50).deletions(10)
            .authoredDate(Instant.now()).build());
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr1.getId()).gitlabCommitSha("sha2")
            .authorEmail("alice@example.com").additions(30).deletions(10)
            .authoredDate(Instant.now()).build());
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr1.getId()).gitlabCommitSha("sha3")
            .authorEmail("bob@example.com").additions(100).deletions(50)
            .authoredDate(Instant.now()).build());

        // MR2: Alice authored, 1 commit by Alice (+200/-0)
        MergeRequest mr2 = mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(project.getId()).gitlabMrId(2L).gitlabMrIid(2L)
            .authorGitlabUserId(100L).state(MrState.MERGED)
            .createdAtGitlab(Instant.parse("2026-04-05T10:00:00Z"))
            .mergedAtGitlab(Instant.parse("2026-04-05T14:00:00Z"))
            .build());

        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr2.getId()).gitlabCommitSha("sha4")
            .authorEmail("alice@example.com").additions(200).deletions(0)
            .authoredDate(Instant.now()).build());
    }

    @Test
    void linesAddedAndDeletedReflectOnlyUserCommits() {
        List<MrSummaryDto> mrs = getMrs(aliceId);

        assertThat(mrs).hasSize(2);

        MrSummaryDto mr1 = mrs.stream().filter(m -> m.commitCount() == 2).findFirst().orElseThrow();
        // Alice's commits in MR1: sha1(+50/-10) + sha2(+30/-10) = +80/-20
        assertThat(mr1.linesAdded()).isEqualTo(80);
        assertThat(mr1.linesDeleted()).isEqualTo(20);
        assertThat(mr1.commitCount()).isEqualTo(2);

        MrSummaryDto mr2 = mrs.stream().filter(m -> m.commitCount() == 1).findFirst().orElseThrow();
        // Alice's commits in MR2: sha4(+200/-0)
        assertThat(mr2.linesAdded()).isEqualTo(200);
        assertThat(mr2.linesDeleted()).isEqualTo(0);
        assertThat(mr2.commitCount()).isEqualTo(1);
    }

    @Test
    void sumOfMrLinesEqualsReportTotal() {
        List<MrSummaryDto> mrs = getMrs(aliceId);

        int totalAdded = mrs.stream().mapToInt(MrSummaryDto::linesAdded).sum();
        int totalDeleted = mrs.stream().mapToInt(MrSummaryDto::linesDeleted).sum();
        int totalCommits = mrs.stream().mapToInt(MrSummaryDto::commitCount).sum();

        // Alice: sha1(+50/-10) + sha2(+30/-10) + sha4(+200/-0) = +280/-20, 3 commits
        assertThat(totalAdded).isEqualTo(280);
        assertThat(totalDeleted).isEqualTo(20);
        assertThat(totalCommits).isEqualTo(3);
    }

    @Test
    void bobSeesOnlyHisOwnCommitLines() {
        // Bob has no MRs authored (authorGitlabUserId=100=Alice), so empty list
        List<MrSummaryDto> mrs = getMrs(bobId);
        assertThat(mrs).isEmpty();
    }

    @Test
    void mrWithNoUserCommitsShowsZeroLines() {
        // Add an MR authored by Alice but with no commits from Alice
        TrackedProject project = trackedProjectRepository.findAllByWorkspaceId(testWorkspaceId).get(0);
        MergeRequest mr3 = mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(project.getId()).gitlabMrId(3L).gitlabMrIid(3L)
            .authorGitlabUserId(100L).state(MrState.MERGED)
            .createdAtGitlab(Instant.parse("2026-04-10T10:00:00Z"))
            .mergedAtGitlab(Instant.parse("2026-04-10T11:00:00Z"))
            .build());
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr3.getId()).gitlabCommitSha("sha5")
            .authorEmail("other@example.com").additions(999).deletions(888)
            .authoredDate(Instant.now()).build());

        List<MrSummaryDto> mrs = getMrs(aliceId);
        MrSummaryDto mr = mrs.stream().filter(m -> m.commitCount() == 0).findFirst().orElseThrow();
        assertThat(mr.linesAdded()).isZero();
        assertThat(mr.linesDeleted()).isZero();
        assertThat(mr.commitCount()).isZero();
    }

    @Test
    void netDiffTakesPrecedenceOverCommitStats() {
        // MR1 has netAdditions/netDeletions set — these must be shown instead of commit stats
        MergeRequest mr1 = mergeRequestRepository.findAll().stream()
            .filter(mr -> mr.getGitlabMrIid() == 1L)
            .findFirst().orElseThrow();
        mr1.setNetAdditions(2114);
        mr1.setNetDeletions(85);
        mergeRequestRepository.save(mr1);

        List<MrSummaryDto> mrs = getMrs(aliceId);
        MrSummaryDto summary = mrs.stream()
            .filter(m -> m.commitCount() == 2)
            .findFirst().orElseThrow();

        // net diff wins over commit stats (+80/-20)
        assertThat(summary.linesAdded()).isEqualTo(2114);
        assertThat(summary.linesDeleted()).isEqualTo(85);
        assertThat(summary.commitCount()).isEqualTo(2); // commit count unchanged
    }

    @Test
    void mergeCommitsExcludedFromFallbackStats() {
        // sha1 is a regular commit (+50/-10), sha_merge is a merge commit with inflated stats
        MergeRequest mr1 = mergeRequestRepository.findAll().stream()
            .filter(mr -> mr.getGitlabMrIid() == 1L)
            .findFirst().orElseThrow();
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr1.getId()).gitlabCommitSha("sha_merge")
            .authorEmail("alice@example.com").additions(19_677).deletions(0)
            .mergeCommit(true)
            .authoredDate(Instant.now()).build());

        List<MrSummaryDto> mrs = getMrs(aliceId);
        MrSummaryDto summary = mrs.stream()
            .filter(m -> m.commitCount() == 3) // 2 regular + 1 merge = 3 total
            .findFirst().orElseThrow();

        // merge commit lines NOT counted; only sha1+sha2 = +80/-20
        assertThat(summary.linesAdded()).isEqualTo(80);
        assertThat(summary.linesDeleted()).isEqualTo(20);
        assertThat(summary.commitCount()).isEqualTo(3);
    }

    private List<MrSummaryDto> getMrs(Long userId) {
        HttpHeaders headers = authHeaders();
        ResponseEntity<List<MrSummaryDto>> resp = restTemplate.exchange(
            "http://localhost:" + port + "/report/user/" + userId + "/mrs?period=LAST_30_DAYS",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {
            });
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody();
    }
}
