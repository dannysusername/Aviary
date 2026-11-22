-- Alert email schedules (specs/014-alert-reminder-frequency).
--
-- Additive only. Hibernate ddl-auto=update adds these columns on boot; the
-- integer columns carry a DEFAULT so existing rows are filled. This file is
-- the explicit record for any DB where ddl-auto is off. PostgreSQL syntax.
--
-- overdue_renudge_days keeps its values and meaning (every N days) and is now
-- the "Overdue" frequency. It can also hold 0 = Off and -1 = Once. New rows
-- get 3 from the entity; existing rows keep what owners set (FR-006).

ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS due_soon_every_days INTEGER NOT NULL DEFAULT 7;   -- 0 Off, -1 Once, 1-90
ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS status_every_days   INTEGER NOT NULL DEFAULT 0;   -- 0 Off, 1-90
ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS time_zone           VARCHAR(255);                 -- IANA id, null = America/New_York
ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS last_due_soon_sent_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS last_overdue_sent_at  TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE alert_preferences ADD COLUMN IF NOT EXISTS last_status_sent_at   TIMESTAMP(6) WITH TIME ZONE;

-- last_checked_on now holds the owner's local date (in time_zone) instead of
-- the server's. No backfill needed: at worst one owner is checked a second
-- time on deploy day, and the schedule rules send nothing new in that case.
