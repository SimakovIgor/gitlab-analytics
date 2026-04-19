package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TrackedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrackedUserRepository extends JpaRepository<TrackedUser, Long> {

    List<TrackedUser> findAllByEnabledTrue();

    List<TrackedUser> findAllByWorkspaceId(Long workspaceId);

    List<TrackedUser> findAllByWorkspaceIdAndEnabledTrue(Long workspaceId);

    @Query("SELECT u.email FROM TrackedUser u WHERE u.email IS NOT NULL AND u.email <> ''")
    List<String> findAllEmails();

    @Query("SELECT u.email FROM TrackedUser u WHERE u.workspaceId = :workspaceId AND u.email IS NOT NULL AND u.email <> ''")
    List<String> findAllEmailsByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
