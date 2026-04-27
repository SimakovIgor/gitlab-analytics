package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.DoraServiceMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DoraServiceMappingRepository extends JpaRepository<DoraServiceMapping, Long> {

    Optional<DoraServiceMapping> findBySourceTypeAndSourceKey(String sourceType, String sourceKey);

    List<DoraServiceMapping> findAllByDoraServiceId(Long doraServiceId);

    List<DoraServiceMapping> findAllBySourceTypeAndSourceKeyIn(String sourceType,
                                                               Collection<String> sourceKeys);
}
