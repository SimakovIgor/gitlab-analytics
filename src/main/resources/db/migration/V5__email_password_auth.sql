-- ── Email + password authentication ──────────────────────────────────────────
-- GitHub OAuth was the only login method. Adding email+password as the primary
-- method while keeping GitHub OAuth working for existing users.
--
-- github_id / github_login become nullable so that users who register via
-- email+password don't need a GitHub identity.

ALTER TABLE app_user ALTER COLUMN github_id DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN github_login DROP NOT NULL;

-- BCrypt-hashed password. NULL for GitHub-only users.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

-- Partial unique index: enforce unique email only for rows that have one.
-- GitHub OAuth users may have null email (private GitHub profile) — nulls
-- never conflict in a unique index, so no existing rows are affected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_email
    ON app_user (email)
    WHERE email IS NOT NULL;
