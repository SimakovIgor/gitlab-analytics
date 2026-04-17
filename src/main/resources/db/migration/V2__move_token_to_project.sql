-- Move token storage from git_source to tracked_project.
-- Each repository has its own Project Access Token in GitLab.

ALTER TABLE git_source
    DROP COLUMN token_encrypted;

ALTER TABLE tracked_project
    ADD COLUMN token_encrypted TEXT NOT NULL DEFAULT '';

ALTER TABLE tracked_project
    ALTER COLUMN token_encrypted DROP DEFAULT;
