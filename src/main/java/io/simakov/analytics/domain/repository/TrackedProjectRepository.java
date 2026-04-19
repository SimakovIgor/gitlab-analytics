package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TrackedProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedProjectRepository extends JpaRepository<TrackedProject, Long> {

    List<TrackedProject> findAllByEnabledTrue();

    List<TrackedProject> findAllByWorkspaceId(Long workspaceId);

    List<TrackedProject> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);

    Optional<TrackedProject> findByGitSourceIdAndGitlabProjectId(Long gitSourceId,
                                                                 Long gitlabProjectId);

    Optional<TrackedProject> findByWorkspaceIdAndGitSourceIdAndGitlabProjectId(Long workspaceId,
                                                                               Long gitSourceId,
                                                                               Long gitlabProjectId);

    Optional<TrackedProject> findFirstByGitSourceId(Long gitSourceId);
}
