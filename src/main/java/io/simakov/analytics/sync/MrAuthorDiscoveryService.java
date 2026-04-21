package io.simakov.analytics.sync;

import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.CommitAuthorEmailProjection;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MrAuthorProjection;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Auto-discovers team members from MR authors after Phase 1 sync completes.
 *
 * <p>After the fast MR-list sync every MergeRequest already has
 * {@code author_gitlab_user_id}, {@code author_name}, and {@code author_username}
 * populated — no extra GitLab API calls needed.
 *
 * <p>Each unique author becomes a {@link TrackedUser} + {@link TrackedUserAlias}.
 * Authors whose {@code gitlab_user_id} is already tracked are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MrAuthorDiscoveryService {

    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestCommitRepository commitRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;

    /**
     * Discovers all distinct MR authors for the given projects and workspace,
     * and saves them as enabled {@link TrackedUser} records with a linked alias.
     *
     * @return number of new users created
     */
    @Transactional
    public int discoverAndSave(Long workspaceId, List<Long> projectIds) {
        List<MrAuthorProjection> authors = mergeRequestRepository.findDistinctAuthorsByProjectIds(projectIds);
        int created = 0;
        for (MrAuthorProjection author : authors) {
            Long gitlabUserId = author.getAuthorGitlabUserId();
            String authorName = author.getAuthorName() != null ? author.getAuthorName() : "Unknown";
            String username = author.getAuthorUsername();

            if (aliasRepository.existsByGitlabUserId(gitlabUserId)) {
                continue;
            }

            TrackedUser user = TrackedUser.builder()
                .workspaceId(workspaceId)
                .displayName(authorName)
                .enabled(true)
                .build();
            user = trackedUserRepository.save(user);

            TrackedUserAlias alias = TrackedUserAlias.builder()
                .trackedUserId(user.getId())
                .gitlabUserId(gitlabUserId)
                .username(username)
                .build();
            aliasRepository.save(alias);
            created++;
        }
        log.info("Auto-discovered {} new team member(s) for workspace={}", created, workspaceId);
        return created;
    }

    /**
     * After ENRICH phase: reads commit author emails from the DB and links them
     * to the matching tracked user aliases (matched by author_gitlab_user_id).
     *
     * <p>Auto-discovered users are created during Phase 1 with a gitlab_user_id alias
     * but no email — commits are not yet synced at that point. This method fills the
     * gap so that commit-based metrics (commits_in_mr_count, active_days, lines_added
     * fallback) are attributed correctly.
     *
     * @return number of new email aliases created
     */
    @Transactional
    public int syncCommitEmails(List<Long> projectIds) {
        List<CommitAuthorEmailProjection> rows = commitRepository.findDistinctCommitEmailsByProjectIds(projectIds);
        int created = 0;
        for (CommitAuthorEmailProjection row : rows) {
            String email = row.getAuthorEmail();
            Long gitlabUserId = row.getGitlabUserId();
            if (email == null || email.isBlank() || gitlabUserId == null) {
                continue;
            }
            TrackedUserAlias existing = aliasRepository.findByGitlabUserId(gitlabUserId).orElse(null);
            if (existing == null) {
                continue; // user not tracked — skip
            }
            if (!aliasRepository.existsByTrackedUserIdAndEmail(existing.getTrackedUserId(), email)) {
                aliasRepository.save(TrackedUserAlias.builder()
                    .trackedUserId(existing.getTrackedUserId())
                    .gitlabUserId(gitlabUserId)
                    .email(email)
                    .username(existing.getUsername())
                    .build());
                created++;
                log.debug("Linked commit email {} to trackedUser={}", email, existing.getTrackedUserId());
            }
        }
        log.info("syncCommitEmails: linked {} new email alias(es) for projects={}", created, projectIds);
        return created;
    }
}
