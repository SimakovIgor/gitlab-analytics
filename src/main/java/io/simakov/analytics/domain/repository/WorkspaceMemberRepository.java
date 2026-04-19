package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    List<WorkspaceMember> findByAppUserId(Long appUserId);

    Optional<WorkspaceMember> findByWorkspaceIdAndAppUserId(Long workspaceId,
                                                            Long appUserId);

    boolean existsByWorkspaceIdAndAppUserId(Long workspaceId,
                                            Long appUserId);
}
