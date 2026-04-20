-- Add is_merge_commit flag to merge_request_commit.
-- Merge commits (parent_ids > 1) have inflated stats that include all changes
-- from the merged branch — they must be excluded from line-count metrics.
-- Existing rows default to FALSE (unknown); re-sync will set the correct value.
ALTER TABLE merge_request_commit
    ADD COLUMN is_merge_commit BOOLEAN NOT NULL DEFAULT FALSE;
