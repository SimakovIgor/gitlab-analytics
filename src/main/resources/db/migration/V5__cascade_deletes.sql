-- Add ON DELETE CASCADE to FK constraints to support hard deletes from the settings UI.
-- Deleting a GitSource cascades to TrackedProject → MergeRequest → (commits, discussions, notes, approvals).
-- Deleting a TrackedUser cascades to TrackedUserAlias and MetricSnapshot.

ALTER TABLE tracked_project
    DROP CONSTRAINT tracked_project_git_source_id_fkey,
    ADD CONSTRAINT fk_tracked_project_git_source
        FOREIGN KEY (git_source_id) REFERENCES git_source (id) ON DELETE CASCADE;

ALTER TABLE tracked_user_alias
    DROP CONSTRAINT tracked_user_alias_tracked_user_id_fkey,
    ADD CONSTRAINT fk_alias_tracked_user
        FOREIGN KEY (tracked_user_id) REFERENCES tracked_user (id) ON DELETE CASCADE;

ALTER TABLE merge_request
    DROP CONSTRAINT merge_request_tracked_project_id_fkey,
    ADD CONSTRAINT fk_merge_request_project
        FOREIGN KEY (tracked_project_id) REFERENCES tracked_project (id) ON DELETE CASCADE;

ALTER TABLE merge_request_commit
    DROP CONSTRAINT merge_request_commit_merge_request_id_fkey,
    ADD CONSTRAINT fk_mr_commit_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE;

ALTER TABLE merge_request_discussion
    DROP CONSTRAINT merge_request_discussion_merge_request_id_fkey,
    ADD CONSTRAINT fk_mr_discussion_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE;

ALTER TABLE merge_request_note
    DROP CONSTRAINT merge_request_note_merge_request_id_fkey,
    DROP CONSTRAINT merge_request_note_discussion_id_fkey,
    ADD CONSTRAINT fk_mr_note_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_mr_note_discussion
        FOREIGN KEY (discussion_id) REFERENCES merge_request_discussion (id) ON DELETE SET NULL;

ALTER TABLE merge_request_approval
    DROP CONSTRAINT merge_request_approval_merge_request_id_fkey,
    ADD CONSTRAINT fk_mr_approval_merge_request
        FOREIGN KEY (merge_request_id) REFERENCES merge_request (id) ON DELETE CASCADE;

ALTER TABLE metric_snapshot
    DROP CONSTRAINT metric_snapshot_tracked_user_id_fkey,
    DROP CONSTRAINT metric_snapshot_tracked_project_id_fkey,
    ADD CONSTRAINT fk_metric_snapshot_user
        FOREIGN KEY (tracked_user_id) REFERENCES tracked_user (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_metric_snapshot_project
        FOREIGN KEY (tracked_project_id) REFERENCES tracked_project (id) ON DELETE CASCADE;
