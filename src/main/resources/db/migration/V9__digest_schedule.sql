-- Per-workspace digest schedule: day of week (MON/TUE/WED/THU/FRI) and hour in Europe/Moscow timezone
ALTER TABLE workspace
    ADD COLUMN IF NOT EXISTS digest_day  VARCHAR(3) NOT NULL DEFAULT 'MON',
    ADD COLUMN IF NOT EXISTS digest_hour INTEGER     NOT NULL DEFAULT 9;
