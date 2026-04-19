package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.GitSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GitSourceRepository extends JpaRepository<GitSource, Long> {

    List<GitSource> findAllByWorkspaceId(Long workspaceId);
}
