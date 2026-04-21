package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TrackedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrackedUserRepository extends JpaRepository<TrackedUser, Long> {

    List<TrackedUser> findAllByWorkspaceId(Long workspaceId);

    List<TrackedUser> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);

    List<TrackedUser> findAllByWorkspaceIdAndIdIn(Long workspaceId,
                                                  List<Long> ids);

    /**
     * All tracked emails for a workspace in one query: user emails UNION alias emails.
     */
    @Query(value = """
        SELECT lower(u.email)
        FROM tracked_user u
        WHERE u.workspace_id = :workspaceId
          AND u.email IS NOT NULL AND u.email <> ''
        UNION
        SELECT lower(a.email)
        FROM tracked_user_alias a
        JOIN tracked_user u ON u.id = a.tracked_user_id
        WHERE u.workspace_id = :workspaceId
          AND a.email IS NOT NULL AND a.email <> ''
        """,
           nativeQuery = true)
    List<String> findAllTrackedEmailsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Returns IDs of enabled tracked users who authored at least one MR in the given projects.
     * Replaces a 3-query chain: findDistinctAuthorIds → findByGitlabUserIdIn → filter by tracked user.
     */
    @Query(value = """
        SELECT DISTINCT tu.id
        FROM tracked_user tu
        JOIN tracked_user_alias tua ON tua.tracked_user_id = tu.id
        JOIN merge_request mr ON mr.author_gitlab_user_id = tua.gitlab_user_id
        WHERE mr.tracked_project_id IN (:projectIds)
          AND tu.workspace_id = :workspaceId
          AND tu.enabled = true
        """,
           nativeQuery = true)
    List<Long> findEnabledIdsByWorkspaceIdAndProjectIds(@Param("workspaceId") Long workspaceId,
                                                        @Param("projectIds") List<Long> projectIds);
}
