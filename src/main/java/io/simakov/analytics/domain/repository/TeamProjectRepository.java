package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.TeamProject;
import io.simakov.analytics.domain.model.TeamProjectId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamProjectRepository extends JpaRepository<TeamProject, TeamProjectId> {

    @Query("SELECT tp.id.projectId FROM TeamProject tp WHERE tp.id.teamId = :teamId")
    List<Long> findProjectIdsByTeamId(@Param("teamId") Long teamId);

    @Query("SELECT tp.id.projectId FROM TeamProject tp WHERE tp.id.teamId IN :teamIds")
    List<Long> findProjectIdsByTeamIdIn(@Param("teamIds") List<Long> teamIds);

    @Modifying
    @Query("DELETE FROM TeamProject tp WHERE tp.id.teamId = :teamId")
    void deleteByTeamId(@Param("teamId") Long teamId);
}
