-- Add fields to support daily rolling-window snapshots with upsert and history queries.

ALTER TABLE metric_snapshot
    ADD COLUMN snapshot_date DATE,
    ADD COLUMN report_mode   VARCHAR(50),
    ADD COLUMN window_days   INT;

-- Backfill any pre-existing rows (none expected, but safe migration practice).
UPDATE metric_snapshot
SET snapshot_date = created_at::date,
    report_mode   = 'MERGED_IN_PERIOD',
    window_days   = 30
WHERE snapshot_date IS NULL;

ALTER TABLE metric_snapshot
    ALTER COLUMN snapshot_date SET NOT NULL,
    ALTER COLUMN report_mode SET NOT NULL,
    ALTER COLUMN window_days SET NOT NULL;

-- Enables upsert: one snapshot per user per calendar date per report mode.
ALTER TABLE metric_snapshot
    ADD CONSTRAINT uq_metric_snapshot_user_date_mode
        UNIQUE (tracked_user_id, snapshot_date, report_mode);

-- Supports history range queries (WHERE snapshot_date BETWEEN :from AND :to).
CREATE INDEX idx_metric_snapshot_snapshot_date ON metric_snapshot (snapshot_date);
