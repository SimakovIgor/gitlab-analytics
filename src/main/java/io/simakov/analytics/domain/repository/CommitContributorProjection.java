package io.simakov.analytics.domain.repository;

/**
 * Projection for commit contributor rows returned by
 * {@link MergeRequestCommitRepository#findContributorRowsByWorkspaceId}.
 * Columns: author_email, author_name, commit_count, repo_name.
 */
public interface CommitContributorProjection {

    String getAuthorEmail();

    String getAuthorName();

    Long getCommitCount();

    String getRepoName();
}
