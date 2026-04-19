-- ── AppUser (GitHub OAuth identity) ─────────────────────────────────────────
CREATE TABLE app_user (
    id            BIGSERIAL    PRIMARY KEY,
    github_id     BIGINT       NOT NULL UNIQUE,
    github_login  VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255),
    avatar_url    VARCHAR(1024),
    email         VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Workspace (tenant) ───────────────────────────────────────────────────────
CREATE TABLE workspace (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(100) NOT NULL UNIQUE,
    owner_id   BIGINT       NOT NULL REFERENCES app_user (id),
    plan       VARCHAR(50)  NOT NULL DEFAULT 'FREE',
    api_token  VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- slug: index already created by UNIQUE constraint (workspace_slug_key)
CREATE INDEX idx_workspace_owner ON workspace (owner_id);

-- ── WorkspaceMember ──────────────────────────────────────────────────────────
CREATE TABLE workspace_member (
    id           BIGSERIAL   PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    app_user_id  BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role         VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    invited_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_member UNIQUE (workspace_id, app_user_id)
);

-- workspace_id: already covered as leading column of uq_workspace_member
CREATE INDEX idx_workspace_member_user ON workspace_member (app_user_id);

-- ── workspace_id FK в tenant-таблицах ───────────────────────────────────────
ALTER TABLE git_source
    ADD COLUMN workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE;

ALTER TABLE tracked_project
    ADD COLUMN workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE;

ALTER TABLE tracked_user
    ADD COLUMN workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE;

ALTER TABLE sync_job
    ADD COLUMN workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE;

ALTER TABLE metric_snapshot
    ADD COLUMN workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE;

-- ── Индексы на workspace_id (только там, где нет покрывающего уникального индекса) ──
CREATE INDEX idx_git_source_workspace   ON git_source (workspace_id);
CREATE INDEX idx_tracked_user_workspace ON tracked_user (workspace_id);
CREATE INDEX idx_sync_job_workspace     ON sync_job (workspace_id);

-- ── Пересоздать UNIQUE constraints с workspace_id ────────────────────────────
ALTER TABLE tracked_project
    DROP CONSTRAINT uq_tracked_project,
    ADD  CONSTRAINT uq_tracked_project
        UNIQUE (workspace_id, git_source_id, gitlab_project_id);

-- uq_tracked_project starts with workspace_id, so workspace_id is covered.
-- Restore index on git_source_id for fk_tracked_project_git_source (was covered by old constraint).
CREATE INDEX idx_tracked_project_git_source ON tracked_project (git_source_id);

ALTER TABLE metric_snapshot
    DROP CONSTRAINT uq_metric_snapshot_user_date,
    ADD  CONSTRAINT uq_metric_snapshot_user_date
        UNIQUE (workspace_id, tracked_user_id, snapshot_date);
-- uq_metric_snapshot_user_date starts with workspace_id, covering workspace_id lookups.
-- tracked_user_id FK is already covered by idx_metric_snapshot_user (partial index, created in V1).
