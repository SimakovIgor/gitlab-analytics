-- AI insight cache — one row per workspace+period+project-set combination.
-- TTL checked in application code (generated_at + 24h).
CREATE TABLE ai_insight_cache (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    BIGINT        NOT NULL,
    -- Period key, e.g. "LAST_30_DAYS"
    period          VARCHAR(32)   NOT NULL,
    -- SHA-256 hex of sorted, comma-joined project IDs (empty string = all projects)
    project_ids_hash VARCHAR(64)  NOT NULL,
    -- JSON array of {kind, title, body} objects
    insights_json   TEXT          NOT NULL,
    tokens_used     INT           NOT NULL DEFAULT 0,
    generated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_ai_insight_cache_ws_period_projects
        UNIQUE (workspace_id, period, project_ids_hash)
);
