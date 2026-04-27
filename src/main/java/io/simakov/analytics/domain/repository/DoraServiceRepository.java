package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.DoraService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoraServiceRepository extends JpaRepository<DoraService, Long> {

    List<DoraService> findAllByWorkspaceId(Long workspaceId);

    Optional<DoraService> findByWorkspaceIdAndName(Long workspaceId, String name);

    Optional<DoraService> findByWorkspaceIdAndNameIgnoreCase(Long workspaceId, String name);
}
