-- Добавляет фазу синхронизации в sync_job.
-- FAST  — первая фаза: только список MR (без коммитов/дискуссий/апрувов/диффов).
-- ENRICH — вторая фаза: полное обогащение данных.
-- NULL  — старые джобы, запущенные до введения фаз (обратная совместимость).
ALTER TABLE sync_job
    ADD COLUMN phase VARCHAR(20);

CREATE INDEX idx_sync_job_phase ON sync_job (phase)
    WHERE phase IS NOT NULL;
