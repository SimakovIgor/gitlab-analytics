-- ReportMode removed: snapshots always use MERGED_IN_PERIOD
ALTER TABLE metric_snapshot
    DROP CONSTRAINT IF EXISTS uq_metric_snapshot_user_date_mode;
ALTER TABLE metric_snapshot
    DROP COLUMN IF EXISTS report_mode;
ALTER TABLE metric_snapshot
    ADD CONSTRAINT uq_metric_snapshot_user_date UNIQUE (tracked_user_id, snapshot_date);
