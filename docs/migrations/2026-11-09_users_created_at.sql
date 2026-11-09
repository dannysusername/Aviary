-- Signup date on accounts (specs/004-public-aviary-stats).
--
-- Additive: Hibernate ddl-auto=update adds this nullable column on boot from
-- entity/User.java (createdAt, set by @PrePersist). This file is the explicit
-- record and covers any DB where ddl-auto is off. PostgreSQL syntax.
--
-- Existing rows stay NULL on purpose. Their real signup date isn't known, and
-- the only other dates (aircraft.created_at) were set by AircraftBackfill on
-- 2026-11-09, so copying them would make every old account look new.
-- See specs/004-public-aviary-stats/research.md R3.

ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE;
