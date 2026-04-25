-- ── Email verification + password reset tokens ────────────────────────────────
-- email_verified: must confirm email before first login.
-- NULL token = verified or never sent.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS email_verified             BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS email_verification_token   VARCHAR(64);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS email_verification_expires_at TIMESTAMPTZ;

-- Auto-verify existing users so existing deployments are not broken.
UPDATE app_user SET email_verified = TRUE;

-- Unique index doubles as the lookup index for token-based verification.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_email_verification_token
    ON app_user (email_verification_token)
    WHERE email_verification_token IS NOT NULL;

-- ── Password reset tokens ──────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS password_reset_token (
    id          BIGSERIAL   PRIMARY KEY,
    app_user_id BIGINT      NOT NULL REFERENCES app_user(id),
    token       VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_password_reset_token_token
    ON password_reset_token (token);

CREATE INDEX IF NOT EXISTS idx_password_reset_token_app_user_id
    ON password_reset_token (app_user_id);
