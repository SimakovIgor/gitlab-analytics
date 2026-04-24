package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.WorkspaceInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, Long> {

    Optional<WorkspaceInvite> findByToken(String token);

    List<WorkspaceInvite> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
