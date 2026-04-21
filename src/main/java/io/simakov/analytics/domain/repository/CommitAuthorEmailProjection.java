package io.simakov.analytics.domain.repository;

public interface CommitAuthorEmailProjection {

    String getAuthorEmail();

    Long getGitlabUserId();
}
