package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequestCommit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MergeRequestCommitRepository extends JpaRepository<MergeRequestCommit, Long> {

    Optional<MergeRequestCommit> findByMergeRequestIdAndGitlabCommitSha(Long mergeRequestId,
                                                                        String sha);

    List<MergeRequestCommit> findByMergeRequestIdIn(List<Long> mergeRequestIds);

    /**
     * Returns rows: [author_email, author_name, commit_count, repo_name].
     * Groups by email + name + repo so callers can aggregate across repos and name variants.
     */
    @Query(nativeQuery = true, value = """
        SELECT mrc.author_email   AS author_email,
               mrc.author_name    AS author_name,
               COUNT(mrc.id)      AS commit_count,
               tp.name            AS repo_name
        FROM merge_request_commit mrc
        JOIN merge_request  mr ON mr.id  = mrc.merge_request_id
        JOIN tracked_project tp ON tp.id = mr.tracked_project_id
        WHERE mrc.author_email IS NOT NULL
          AND mrc.author_email <> ''
        GROUP BY mrc.author_email, mrc.author_name, tp.name
        ORDER BY commit_count DESC
        """)
    List<Object[]> findContributorRows();
}
