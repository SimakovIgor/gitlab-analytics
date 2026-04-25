package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByApiToken(String apiToken);

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Workspace> findAllByDigestEnabled(boolean digestEnabled);
}
