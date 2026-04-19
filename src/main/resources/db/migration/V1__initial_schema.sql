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

-- ── GitLab instance connections ───────────────────────────────────────────────
CREATE TABLE git_source
(
    id           BIGSERIAL    PRIMARY KEY,
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    base_url     VARCHAR(512) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_git_source_workspace ON git_source (workspace_id);

-- ── Repositories to track ─────────────────────────────────────────────────────
CREATE TABLE tracked_project
(
    id                  BIGSERIAL    PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    git_source_id       BIGINT       NOT NULL,
    gitlab_project_id   BIGINT       NOT NULL,
    path_with_namespace VARCHAR(512) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    token_encrypted     TEXT         NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tracked_project UNIQUE (workspace_id, git_source_id, gitlab_project_id),
    CONSTRAINT fk_tracked_project_git_source
        FOREIGN KEY (git_source_id) REFERENCES git_source (id) ON DELETE CASCADE
);

-- uq_tracked_project starts with workspace_id (covering workspace_id lookups).
-- Separate index for git_source_id FK coverage (not the leading column in uq_tracked_project).
CREATE INDEX idx_tracked_project_git_source ON tracked_project (git_source_id);

-- ── Team members ──────────────────────────────────────────────────────────────
CREATE TABLE tracked_user
(
    id           BIGSERIAL    PRIMARY KEY,
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    display_name VARCHAR(255) NOT NULL,
    email        VARCHAR(255),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tracked_user_workspace ON tracked_user (workspace_id);

-- ── GitLab identities linked to a tracked user ───────────────────────────────
CREATE TABLE tracked_user_alias
(
    id              BIGSERIAL    PRIMARY KEY,
    tracked_user_id BIGINT       NOT NULL,
    gitlab_user_id  BIGINT,
    username        VARCHAR(255),
    email           VARCHAR(255),
    name            VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_alias_tracked_user
        FOREIGN KEY (tracked_user_id) REFERENCES tracked_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_tracked_user_alias_tracked_user_id ON tracked_user_alias (tracked_user_id);
CREATE INDEX idx_alias_gitlab_user_id ON tracked_user_alias (gitlab_user_id) WHERE gitlab_user_id IS NOT NULL;
CREATE INDEX idx_alias_username ON tracked_user_alias (username) WHERE username IS NOT NULL;
CREATE INDEX idx_alias_email ON tracked_user_alias (email) WHERE email IS NOT NULL;

-- ── Merge requests ────────────────────────────────────────────────────────────
CREATE TABLE merge_request
(
    id                       BIGSERIAL    PRIMARY KEY,
    tracked_project_id       BIGINT       NOT NULL,
    gitlab_mr_id             BIGINT       NOT NULL,
    gitlab_mr_iid            BIGINT       NOT NULL,
    title                    VARCHAR(1024),
    description              TEXT,
    state                    VARCHAR(50)  NOT NULL,
    author_gitlab_user_id    BIGINT,
    author_username          VARCHAR(255),
    author_name              VARCHAR(255),
    created_at_gitlab        TIMESTAMPTZ  NOT NULL,
    merged_at_gitlab         TIMESTAMPTZ,
    closed_at_gitlab         TIMESTAMPTZ,
    merged_by_gitlab_user_id BIGINT,
    additions                INT          NOT NULL DEFAULT 0,
    deletions                INT          NOT NULL DEFAULT 0,
    changes_count            INT          NOT NULL DEFAULT 0,
    files_changed_count      INT          NOT NULL DEFAULT 0,
    web_url                  VARCHAR(1024),
    first_commit_at          TIMESTAMPTZ,
    last_commit_at           TIMESTAMPTZ,
    updated_at_gitlab        TIMESTAMPTZ,
    synced_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merge_request UNIQUE (tracked_project_id, gitlab_mr_id),
    CONSTRAINT fk_merge_request_project
        FOREIGN KEY (tracked_project_id) REFERENCES tracked_project (id) ON DELETE CASCADE
);

CREATE INDEX idx_mr_author ON merge_request (author_gitlab_user_id) WHERE author_gitlab_user_id IS NOT NULL;
CREATE INDEX idx_mr_created ON merge_request (created_at_gitlab);
CREATE INDEX idx_mr_merged ON merge_request (merged_at_gitlab) WHERE merged_at_gitlab IS NOT NULL;
CREATE INDEX idx_mr_state ON merge_request (state);

-- ── Commits within an MR ──────────────────────────────────────────────────────
CREATE TABLE merge_request_commit
(
    id                  BIGSERIAL   PRIMARY KEY,
    merge_request_id    BIGINT      NOT NULL,
    gitlab_commit_sha   VARCHAR(64) NOT NULL,
    author_name         VARCHAR(255),
    author_email        VARCHAR(255),
    authored_date       TIMESTAMPTZ,
    committed_date      TIMESTAMPTZ,
    additions           INT         NOT NULL DEFAULT 0,
    deletions           INT         NOT NULL DEFAULT 0,
    total_changes       INT         NOT NULL DEFAULT 0,
    files_changed_count INT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_mr_commit UNIQUE (merge_request_id, gitlab_commit_sha),
    CONSTRAINT fk_mr_commit_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE
);

CREATE INDEX idx_mr_commit_email ON merge_request_commit (author_email) WHERE author_email IS NOT NULL;

-- ── Discussion threads on an MR ───────────────────────────────────────────────
CREATE TABLE merge_request_discussion
(
    id                   BIGSERIAL   PRIMARY KEY,
    merge_request_id     BIGINT      NOT NULL,
    gitlab_discussion_id VARCHAR(64) NOT NULL,
    individual_note      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at_gitlab    TIMESTAMPTZ,
    CONSTRAINT uq_mr_discussion UNIQUE (merge_request_id, gitlab_discussion_id),
    CONSTRAINT fk_mr_discussion_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE
);

-- ── Individual notes / comments ───────────────────────────────────────────────
CREATE TABLE merge_request_note
(
    id                    BIGSERIAL NOT NULL PRIMARY KEY,
    merge_request_id      BIGINT    NOT NULL,
    discussion_id         BIGINT,
    gitlab_note_id        BIGINT    NOT NULL,
    author_gitlab_user_id BIGINT,
    author_username       VARCHAR(255),
    author_name           VARCHAR(255),
    body                  TEXT,
    system                BOOLEAN   NOT NULL DEFAULT FALSE,
    internal              BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at_gitlab     TIMESTAMPTZ,
    updated_at_gitlab     TIMESTAMPTZ,
    CONSTRAINT uq_mr_note UNIQUE (merge_request_id, gitlab_note_id),
    CONSTRAINT fk_mr_note_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_mr_note_discussion
        FOREIGN KEY (discussion_id) REFERENCES merge_request_discussion (id) ON DELETE SET NULL
);

CREATE INDEX idx_mr_note_author ON merge_request_note (author_gitlab_user_id) WHERE author_gitlab_user_id IS NOT NULL;
CREATE INDEX idx_mr_note_discussion_id ON merge_request_note (discussion_id) WHERE discussion_id IS NOT NULL;

-- ── Approvals per MR ──────────────────────────────────────────────────────────
CREATE TABLE merge_request_approval
(
    id                         BIGSERIAL NOT NULL PRIMARY KEY,
    merge_request_id           BIGINT    NOT NULL,
    approved_by_gitlab_user_id BIGINT,
    approved_by_username       VARCHAR(255),
    approved_by_name           VARCHAR(255),
    approved_at_gitlab         TIMESTAMPTZ,
    CONSTRAINT uq_mr_approval UNIQUE (merge_request_id, approved_by_gitlab_user_id),
    CONSTRAINT fk_mr_approval_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE
);

-- ── Sync job audit log ────────────────────────────────────────────────────────
CREATE TABLE sync_job
(
    id            BIGSERIAL    PRIMARY KEY,
    workspace_id  BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    status        VARCHAR(50)  NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    requested_by  VARCHAR(255),
    date_from     TIMESTAMPTZ,
    date_to       TIMESTAMPTZ,
    payload_json  JSONB,
    error_message TEXT,
    total_mrs     INT          NOT NULL DEFAULT 0,
    processed_mrs INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_sync_job_status    ON sync_job (status);
CREATE INDEX idx_sync_job_workspace ON sync_job (workspace_id);

-- ── Cached metric snapshots ───────────────────────────────────────────────────
CREATE TABLE metric_snapshot
(
    id                 BIGSERIAL    PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    tracked_user_id    BIGINT,
    tracked_project_id BIGINT,
    period_type        VARCHAR(50)  NOT NULL,
    snapshot_date      DATE         NOT NULL,
    window_days        INT          NOT NULL,
    date_from          TIMESTAMPTZ  NOT NULL,
    date_to            TIMESTAMPTZ  NOT NULL,
    scope_type         VARCHAR(50)  NOT NULL,
    metrics_json       JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_metric_snapshot_user_date UNIQUE (workspace_id, tracked_user_id, snapshot_date),
    CONSTRAINT fk_metric_snapshot_user
        FOREIGN KEY (tracked_user_id) REFERENCES tracked_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_metric_snapshot_project
        FOREIGN KEY (tracked_project_id) REFERENCES tracked_project (id) ON DELETE CASCADE
);

-- uq_metric_snapshot_user_date starts with workspace_id (covering workspace_id lookups).
CREATE INDEX idx_metric_snapshot_user         ON metric_snapshot (tracked_user_id)    WHERE tracked_user_id IS NOT NULL;
CREATE INDEX idx_metric_snapshot_project      ON metric_snapshot (tracked_project_id) WHERE tracked_project_id IS NOT NULL;
CREATE INDEX idx_metric_snapshot_snapshot_date ON metric_snapshot (snapshot_date);
