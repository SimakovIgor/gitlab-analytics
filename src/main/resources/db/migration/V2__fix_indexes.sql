-- Fix redundant single-column indexes that duplicate unique constraint indexes.
-- Each unique constraint already creates a B-tree index with the FK column as its leftmost key,
-- so PostgreSQL can use it for FK lookups and range scans on that column alone.

DROP INDEX IF EXISTS idx_mr_project;          -- covered by uq_merge_request (tracked_project_id, gitlab_mr_id)
DROP INDEX IF EXISTS idx_mr_commit_mr_id;     -- covered by uq_mr_commit (merge_request_id, gitlab_commit_sha)
DROP INDEX IF EXISTS idx_mr_discussion_mr_id; -- covered by uq_mr_discussion (merge_request_id, gitlab_discussion_id)
DROP INDEX IF EXISTS idx_mr_note_mr_id;       -- covered by uq_mr_note (merge_request_id, gitlab_note_id)
DROP INDEX IF EXISTS idx_mr_approval_mr_id;   -- covered by uq_mr_approval (merge_request_id, approved_by_gitlab_user_id)

-- Add missing indexes on FK columns not covered by any existing index.

CREATE INDEX idx_tracked_user_alias_tracked_user_id
    ON tracked_user_alias (tracked_user_id);

CREATE INDEX idx_mr_note_discussion_id
    ON merge_request_note (discussion_id)
    WHERE discussion_id IS NOT NULL;

-- Convert non-partial indexes on nullable columns to partial indexes
-- to exclude NULL values and avoid flagging by INDEXES_WITH_NULL_VALUES.

DROP INDEX IF EXISTS idx_mr_author;
CREATE INDEX idx_mr_author
    ON merge_request (author_gitlab_user_id)
    WHERE author_gitlab_user_id IS NOT NULL;

DROP INDEX IF EXISTS idx_mr_note_author;
CREATE INDEX idx_mr_note_author
    ON merge_request_note (author_gitlab_user_id)
    WHERE author_gitlab_user_id IS NOT NULL;

DROP INDEX IF EXISTS idx_mr_commit_email;
CREATE INDEX idx_mr_commit_email
    ON merge_request_commit (author_email)
    WHERE author_email IS NOT NULL;

DROP INDEX IF EXISTS idx_metric_snapshot_user;
CREATE INDEX idx_metric_snapshot_user
    ON metric_snapshot (tracked_user_id)
    WHERE tracked_user_id IS NOT NULL;

DROP INDEX IF EXISTS idx_metric_snapshot_project;
CREATE INDEX idx_metric_snapshot_project
    ON metric_snapshot (tracked_project_id)
    WHERE tracked_project_id IS NOT NULL;
