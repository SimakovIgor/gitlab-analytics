package io.simakov.analytics.domain.repository;

/**
 * Projection for distinct MR authors returned by
 * {@link MergeRequestRepository#findDistinctAuthorsByProjectIds}.
 */
public interface MrAuthorProjection {

    Long getAuthorGitlabUserId();

    String getAuthorName();

    String getAuthorUsername();
}
