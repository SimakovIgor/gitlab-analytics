CREATE TABLE IF NOT EXISTS workspace_invite (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        BIGINT      NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    token               VARCHAR(64) NOT NULL,
    role                VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_by          BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    used_by_app_user_id BIGINT REFERENCES app_user (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_invite_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_workspace_invite_workspace  ON workspace_invite (workspace_id);
CREATE INDEX IF NOT EXISTS idx_workspace_invite_created_by ON workspace_invite (created_by);
CREATE INDEX IF NOT EXISTS idx_workspace_invite_used_by
    ON workspace_invite (used_by_app_user_id)
    WHERE used_by_app_user_id IS NOT NULL;
