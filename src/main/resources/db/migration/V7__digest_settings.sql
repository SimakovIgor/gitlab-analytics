-- Weekly digest settings per workspace
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS digest_enabled BOOLEAN NOT NULL DEFAULT TRUE;
