-- Universal DORA event store.
-- Decouples DORA metric calculation from specific sources (GitLab/Jira/Jenkins/etc.).
-- Source adapters and Manual API write here; DORA core reads from here.

-- A deployable service/application unit (maps to one or more repositories).
CREATE TABLE dora_service (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dora_service_workspace_name UNIQUE (workspace_id, name)
);

CREATE INDEX idx_dora_service_workspace ON dora_service (workspace_id);

-- Maps external source identifiers to a dora_service.
-- Example: sourceType=GITLAB, sourceKey=<project_id>
--          sourceType=JIRA,   sourceKey=<component_name>
CREATE TABLE dora_service_mapping (
    id              BIGSERIAL PRIMARY KEY,
    dora_service_id BIGINT       NOT NULL REFERENCES dora_service (id) ON DELETE CASCADE,
    source_type     VARCHAR(64)  NOT NULL,
    source_key      VARCHAR(512) NOT NULL,
    CONSTRAINT uq_dora_service_mapping UNIQUE (dora_service_id, source_type, source_key)
);

CREATE INDEX idx_dora_service_mapping_lookup ON dora_service_mapping (source_type, source_key);

-- Normalized deployment event from any source.
-- dora_service_id is nullable: unmatched events are stored for diagnostics.
CREATE TABLE dora_deploy_event (
    id                BIGSERIAL PRIMARY KEY,
    workspace_id      BIGINT                   NOT NULL,
    dora_service_id   BIGINT                   REFERENCES dora_service (id),
    environment       VARCHAR(128)             NOT NULL,
    deployed_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    status            VARCHAR(32)              NOT NULL,
    source            VARCHAR(64)              NOT NULL,
    version           VARCHAR(255),
    commit_sha        VARCHAR(128),
    commit_range_from VARCHAR(128),
    commit_range_to   VARCHAR(128),
    idempotency_key   VARCHAR(512),
    external_id       VARCHAR(512),
    external_url      VARCHAR(2048),
    metadata          JSONB,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dora_deploy_idempotency UNIQUE (workspace_id, idempotency_key)
);

CREATE INDEX idx_dora_deploy_workspace ON dora_deploy_event (workspace_id);
CREATE INDEX idx_dora_deploy_service ON dora_deploy_event (dora_service_id)
    WHERE dora_service_id IS NOT NULL;
CREATE INDEX idx_dora_deploy_deployed_at ON dora_deploy_event (deployed_at);

-- Normalized incident event from any source.
-- dora_service_id is nullable: unmatched events are stored for diagnostics.
CREATE TABLE dora_incident_event (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        BIGINT                   NOT NULL,
    dora_service_id     BIGINT                   REFERENCES dora_service (id),
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at         TIMESTAMP WITH TIME ZONE,
    severity            VARCHAR(32),
    status              VARCHAR(32),
    source              VARCHAR(64)              NOT NULL,
    caused_by_deploy_id BIGINT                   REFERENCES dora_deploy_event (id),
    idempotency_key     VARCHAR(512),
    external_id         VARCHAR(512),
    external_url        VARCHAR(2048),
    metadata            JSONB,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dora_incident_idempotency UNIQUE (workspace_id, idempotency_key)
);

CREATE INDEX idx_dora_incident_workspace ON dora_incident_event (workspace_id);
CREATE INDEX idx_dora_incident_service ON dora_incident_event (dora_service_id)
    WHERE dora_service_id IS NOT NULL;
CREATE INDEX idx_dora_incident_started_at ON dora_incident_event (started_at);
