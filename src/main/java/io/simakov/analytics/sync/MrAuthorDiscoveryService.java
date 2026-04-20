package io.simakov.analytics.sync;

import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
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
}
