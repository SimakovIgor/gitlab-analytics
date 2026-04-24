-- Team: именованная группа TrackedUser-ов внутри workspace
CREATE TABLE team (
    id           BIGSERIAL    PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    color_index  INTEGER      NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_team_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,

    -- Имя команды уникально в рамках workspace
    CONSTRAINT uq_team_workspace_name
        UNIQUE (workspace_id, name)
);

-- idx_team_workspace не нужен: uq_team_workspace_name(workspace_id, name) уже покрывает запросы по workspace_id

-- M:1: каждый разработчик принадлежит не более чем одной команде
ALTER TABLE tracked_user ADD COLUMN team_id BIGINT;

ALTER TABLE tracked_user
    ADD CONSTRAINT fk_tracked_user_team
        FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE SET NULL;

-- Partial index: покрывает только non-null значения — удовлетворяет оба правила pg-index-health
-- (FOREIGN_KEYS_WITHOUT_INDEX + INDEXES_WITH_NULL_VALUES)
CREATE INDEX idx_tracked_user_team ON tracked_user(team_id)
    WHERE team_id IS NOT NULL;
