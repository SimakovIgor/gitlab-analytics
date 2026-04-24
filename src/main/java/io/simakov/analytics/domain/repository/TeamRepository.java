package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByWorkspaceId(Long workspaceId);

    Optional<Team> findByWorkspaceIdAndId(Long workspaceId, Long id);

    int countByWorkspaceId(Long workspaceId);

    void deleteByWorkspaceIdAndId(Long workspaceId, Long id);
}
