package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TrackedUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackedUserRepository extends JpaRepository<TrackedUser, Long> {

    List<TrackedUser> findAllByEnabledTrue();

    List<TrackedUser> findAllByWorkspaceId(Long workspaceId);

    List<TrackedUser> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);
}
