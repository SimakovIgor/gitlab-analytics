-- Add missing partial index for the nullable FK dora_incident_event.caused_by_deploy_id
CREATE INDEX idx_dora_incident_caused_by ON dora_incident_event (caused_by_deploy_id)
    WHERE caused_by_deploy_id IS NOT NULL;

-- Drop redundant workspace_id-only indexes that intersect with the unique constraint indexes.
-- The UNIQUE constraints on (workspace_id, idempotency_key) and (workspace_id, name) already
-- create B-tree indexes that serve workspace_id prefix queries, making these standalone
-- workspace_id indexes unnecessary (pg-index-health INTERSECTED_INDEXES check).
DROP INDEX idx_dora_service_workspace;
DROP INDEX idx_dora_deploy_workspace;
DROP INDEX idx_dora_incident_workspace;
