package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TrackedUserAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackedUserAliasRepository extends JpaRepository<TrackedUserAlias, Long> {

    List<TrackedUserAlias> findByTrackedUserId(Long trackedUserId);

    List<TrackedUserAlias> findByTrackedUserIdIn(List<Long> trackedUserIds);
}
