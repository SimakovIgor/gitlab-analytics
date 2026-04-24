package io.simakov.analytics.domain.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "team_project")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamProject {

    @EmbeddedId
    private TeamProjectId id;

    public static TeamProject of(Long teamId, Long projectId) {
        return new TeamProject(new TeamProjectId(teamId, projectId));
    }
}
