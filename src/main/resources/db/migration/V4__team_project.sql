CREATE TABLE team_project (
    team_id    BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    CONSTRAINT pk_team_project PRIMARY KEY (team_id, project_id),
    CONSTRAINT fk_team_project_team    FOREIGN KEY (team_id)    REFERENCES team(id)            ON DELETE CASCADE,
    CONSTRAINT fk_team_project_project FOREIGN KEY (project_id) REFERENCES tracked_project(id) ON DELETE CASCADE
);

-- Index on project_id for FK lookup; team_id is covered by the PK as leading column
CREATE INDEX idx_team_project_project ON team_project(project_id);
