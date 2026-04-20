-- Net diff stats fetched from GET /merge_requests/:iid/diffs — matches GitLab UI.
-- NULL means not yet fetched (re-sync with fetchDiffStats=true to populate).
ALTER TABLE merge_request
    ADD COLUMN net_additions INT,
    ADD COLUMN net_deletions INT;
