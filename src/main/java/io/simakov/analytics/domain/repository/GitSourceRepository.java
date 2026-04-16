package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.GitSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitSourceRepository extends JpaRepository<GitSource, Long> {

}
