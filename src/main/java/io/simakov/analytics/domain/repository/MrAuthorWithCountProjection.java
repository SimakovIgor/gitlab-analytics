package io.simakov.analytics.domain.repository;

public interface MrAuthorWithCountProjection {

    Long getAuthorGitlabUserId();

    String getAuthorName();

    String getAuthorUsername();

    Long getMrCount();
}
