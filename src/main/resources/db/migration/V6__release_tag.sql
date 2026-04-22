-- ── Release tags (GitLab releases / tags) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS release_tag
(
    id                BIGSERIAL PRIMARY KEY,
    tracked_project_id BIGINT       NOT NULL REFERENCES tracked_project (id) ON DELETE CASCADE,
    tag_name          VARCHAR(255)  NOT NULL,
    tag_created_at    TIMESTAMPTZ   NOT NULL,
    released_at       TIMESTAMPTZ,
    pipeline_id       BIGINT,
    stage_deployed_at TIMESTAMPTZ,
    prod_deployed_at  TIMESTAMPTZ,
    synced_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_release_tag_project_tag UNIQUE (tracked_project_id, tag_name)
);

CREATE INDEX IF NOT EXISTS idx_release_tag_project ON release_tag (tracked_project_id);
CREATE INDEX IF NOT EXISTS idx_release_tag_created ON release_tag (tracked_project_id, tag_created_at);

-- ── Link MRs to the release they shipped in ──────────────────────────────────
ALTER TABLE merge_request
    ADD COLUMN IF NOT EXISTS release_tag_id BIGINT REFERENCES release_tag (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_merge_request_release_tag
    ON merge_request (release_tag_id)
    WHERE release_tag_id IS NOT NULL;
