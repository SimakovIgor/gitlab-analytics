-- GitLab instance connections
CREATE TABLE git_source
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    base_url        VARCHAR(512) NOT NULL,
    token_encrypted TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Repositories to track
CREATE TABLE tracked_project
(
    id                  BIGSERIAL PRIMARY KEY,
    git_source_id       BIGINT       NOT NULL REFERENCES git_source (id),
    gitlab_project_id   BIGINT       NOT NULL,
    path_with_namespace VARCHAR(512) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tracked_project UNIQUE (git_source_id, gitlab_project_id)
);

-- Team members
CREATE TABLE tracked_user
(
    id           BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    email        VARCHAR(255),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- GitLab identities linked to a tracked user
CREATE TABLE tracked_user_alias
(
    id              BIGSERIAL PRIMARY KEY,
    tracked_user_id BIGINT      NOT NULL REFERENCES tracked_user (id),
    gitlab_user_id  BIGINT,
    username        VARCHAR(255),
    email           VARCHAR(255),
    name            VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alias_gitlab_user_id ON tracked_user_alias (gitlab_user_id)
    WHERE gitlab_user_id IS NOT NULL;
CREATE INDEX idx_alias_username ON tracked_user_alias (username)
    WHERE username IS NOT NULL;
CREATE INDEX idx_alias_email ON tracked_user_alias (email)
    WHERE email IS NOT NULL;

-- Merge requests
CREATE TABLE merge_request
(
    id                       BIGSERIAL PRIMARY KEY,
    tracked_project_id       BIGINT      NOT NULL REFERENCES tracked_project (id),
    gitlab_mr_id             BIGINT      NOT NULL,
    gitlab_mr_iid            BIGINT      NOT NULL,
    title                    VARCHAR(1024),
    description              TEXT,
    state                    VARCHAR(50) NOT NULL,
    author_gitlab_user_id    BIGINT,
    author_username          VARCHAR(255),
    author_name              VARCHAR(255),
    created_at_gitlab        TIMESTAMPTZ NOT NULL,
    merged_at_gitlab         TIMESTAMPTZ,
    closed_at_gitlab         TIMESTAMPTZ,
    merged_by_gitlab_user_id BIGINT,
    additions                INT         NOT NULL DEFAULT 0,
    deletions                INT         NOT NULL DEFAULT 0,
    changes_count            INT         NOT NULL DEFAULT 0,
    files_changed_count      INT         NOT NULL DEFAULT 0,
    web_url                  VARCHAR(1024),
    first_commit_at          TIMESTAMPTZ,
    last_commit_at           TIMESTAMPTZ,
    updated_at_gitlab        TIMESTAMPTZ,
    synced_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merge_request UNIQUE (tracked_project_id, gitlab_mr_id)
);

CREATE INDEX idx_mr_project ON merge_request (tracked_project_id);
CREATE INDEX idx_mr_author ON merge_request (author_gitlab_user_id);
CREATE INDEX idx_mr_created ON merge_request (created_at_gitlab);
CREATE INDEX idx_mr_merged ON merge_request (merged_at_gitlab) WHERE merged_at_gitlab IS NOT NULL;
CREATE INDEX idx_mr_state ON merge_request (state);

-- Commits within an MR
CREATE TABLE merge_request_commit
(
    id                  BIGSERIAL PRIMARY KEY,
    merge_request_id    BIGINT      NOT NULL REFERENCES merge_request (id),
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
    CONSTRAINT uq_mr_commit UNIQUE (merge_request_id, gitlab_commit_sha)
);

CREATE INDEX idx_mr_commit_mr_id ON merge_request_commit (merge_request_id);
CREATE INDEX idx_mr_commit_email ON merge_request_commit (author_email);

-- Discussion threads on an MR
CREATE TABLE merge_request_discussion
(
    id                   BIGSERIAL PRIMARY KEY,
    merge_request_id     BIGINT      NOT NULL REFERENCES merge_request (id),
    gitlab_discussion_id VARCHAR(64) NOT NULL,
    individual_note      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at_gitlab    TIMESTAMPTZ,
    CONSTRAINT uq_mr_discussion UNIQUE (merge_request_id, gitlab_discussion_id)
);

CREATE INDEX idx_mr_discussion_mr_id ON merge_request_discussion (merge_request_id);

-- Individual notes / comments
CREATE TABLE merge_request_note
(
    id                    BIGSERIAL PRIMARY KEY,
    merge_request_id      BIGINT  NOT NULL REFERENCES merge_request (id),
    discussion_id         BIGINT REFERENCES merge_request_discussion (id),
    gitlab_note_id        BIGINT  NOT NULL,
    author_gitlab_user_id BIGINT,
    author_username       VARCHAR(255),
    author_name           VARCHAR(255),
    body                  TEXT,
    system                BOOLEAN NOT NULL DEFAULT FALSE,
    internal              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_gitlab     TIMESTAMPTZ,
    updated_at_gitlab     TIMESTAMPTZ,
    CONSTRAINT uq_mr_note UNIQUE (merge_request_id, gitlab_note_id)
);

CREATE INDEX idx_mr_note_mr_id ON merge_request_note (merge_request_id);
CREATE INDEX idx_mr_note_author ON merge_request_note (author_gitlab_user_id);

-- Approvals per MR
CREATE TABLE merge_request_approval
(
    id                         BIGSERIAL PRIMARY KEY,
    merge_request_id           BIGINT NOT NULL REFERENCES merge_request (id),
    approved_by_gitlab_user_id BIGINT,
    approved_by_username       VARCHAR(255),
    approved_by_name           VARCHAR(255),
    approved_at_gitlab         TIMESTAMPTZ,
    CONSTRAINT uq_mr_approval UNIQUE (merge_request_id, approved_by_gitlab_user_id)
);

CREATE INDEX idx_mr_approval_mr_id ON merge_request_approval (merge_request_id);

-- Sync job audit log
CREATE TABLE sync_job
(
    id            BIGSERIAL PRIMARY KEY,
    status        VARCHAR(50) NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    requested_by  VARCHAR(255),
    date_from     TIMESTAMPTZ,
    date_to       TIMESTAMPTZ,
    payload_json  JSONB,
    error_message TEXT
);

CREATE INDEX idx_sync_job_status ON sync_job (status);

-- Cached metric snapshots (optional; API calculates on-the-fly for MVP)
CREATE TABLE metric_snapshot
(
    id                 BIGSERIAL PRIMARY KEY,
    tracked_user_id    BIGINT REFERENCES tracked_user (id),
    tracked_project_id BIGINT REFERENCES tracked_project (id),
    period_type        VARCHAR(50) NOT NULL,
    date_from          TIMESTAMPTZ NOT NULL,
    date_to            TIMESTAMPTZ NOT NULL,
    scope_type         VARCHAR(50) NOT NULL,
    metrics_json       JSONB       NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_metric_snapshot_user ON metric_snapshot (tracked_user_id);
CREATE INDEX idx_metric_snapshot_project ON metric_snapshot (tracked_project_id);
