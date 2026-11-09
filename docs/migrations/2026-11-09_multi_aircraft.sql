-- Multiple aircraft per account (specs/003-multi-aircraft-fleet).
--
-- Hibernate ddl-auto=update creates the new `aircraft` table and adds the
-- nullable aircraft_id / last_viewed_aircraft_id columns on boot. It never
-- DROPS anything, so the old one-subscription-per-user unique constraint
-- would stay and block a second aircraft's subscription. AircraftBackfill
-- (config/AircraftBackfill.java) drops it and attaches existing data on every
-- boot, idempotently. This file is the explicit record and covers any DB
-- where ddl-auto is off. PostgreSQL syntax.

-- 1. New table (what Hibernate generates from entity/Aircraft.java).
CREATE TABLE IF NOT EXISTS aircraft (
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         BIGINT NOT NULL REFERENCES users(id),
    tail_number                     VARCHAR(255),
    tail_number_key                 VARCHAR(255),
    make_model                      VARCHAR(255),
    owner_name                      VARCHAR(255),
    make_modelsn                    VARCHAR(255),
    block_time_hours                DOUBLE PRECISION,
    time_in_service_hours           DOUBLE PRECISION,
    block_time_manual_baseline      DOUBLE PRECISION,
    time_in_service_manual_baseline DOUBLE PRECISION,
    block_time_updated_at           TIMESTAMP WITH TIME ZONE,
    time_in_service_updated_at      TIMESTAMP WITH TIME ZONE,
    block_time_updated_source       VARCHAR(255),
    time_in_service_updated_source  VARCHAR(255),
    created_at                      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_aircraft_user_tail UNIQUE (user_id, tail_number_key)
);

-- 2. New columns.
ALTER TABLE service_timelines  ADD COLUMN IF NOT EXISTS aircraft_id BIGINT REFERENCES aircraft(id);
ALTER TABLE flight_logs        ADD COLUMN IF NOT EXISTS aircraft_id BIGINT REFERENCES aircraft(id);
ALTER TABLE flight_suggestions ADD COLUMN IF NOT EXISTS aircraft_id BIGINT REFERENCES aircraft(id);
ALTER TABLE subscriptions      ADD COLUMN IF NOT EXISTS aircraft_id BIGINT REFERENCES aircraft(id);
ALTER TABLE import_proposals   ADD COLUMN IF NOT EXISTS aircraft_id BIGINT REFERENCES aircraft(id);
ALTER TABLE users              ADD COLUMN IF NOT EXISTS last_viewed_aircraft_id BIGINT REFERENCES aircraft(id);

-- 3. One subscription per AIRCRAFT, no longer per user (non-additive).
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS uq_subscriptions_user;
-- Hibernate's own name for the old @JoinColumn(unique = true) varies; find it with:
--   SELECT conname FROM pg_constraint WHERE conrelid = 'subscriptions'::regclass AND contype = 'u';
-- and drop any that cover only user_id.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_subscriptions_aircraft') THEN
        ALTER TABLE subscriptions ADD CONSTRAINT uq_subscriptions_aircraft UNIQUE (aircraft_id);
    END IF;
END $$;

-- 4. Backfill: each existing user's data becomes its first aircraft.
--    The legacy users columns are kept (unmapped) for one release so a code
--    rollback still finds them.
INSERT INTO aircraft (user_id, tail_number, tail_number_key, make_model, owner_name, make_modelsn,
                      block_time_hours, time_in_service_hours,
                      block_time_manual_baseline, time_in_service_manual_baseline,
                      block_time_updated_at, time_in_service_updated_at,
                      block_time_updated_source, time_in_service_updated_source, created_at)
SELECT u.id, UPPER(TRIM(u.tail_number)),
       NULLIF(UPPER(REGEXP_REPLACE(COALESCE(u.tail_number, ''), '\s', '', 'g')), ''),
       u.make_model, u.owner_name, u.make_modelsn,
       COALESCE(u.block_time_hours, 0), COALESCE(u.time_in_service_hours, 0),
       u.block_time_manual_baseline, u.time_in_service_manual_baseline,
       u.block_time_updated_at, u.time_in_service_updated_at,
       u.block_time_updated_source, u.time_in_service_updated_source, NOW()
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM aircraft a WHERE a.user_id = u.id);

UPDATE service_timelines t  SET aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = t.user_id) WHERE t.aircraft_id IS NULL;
UPDATE flight_logs f        SET aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = f.user_id) WHERE f.aircraft_id IS NULL;
UPDATE flight_suggestions s SET aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = s.user_id) WHERE s.aircraft_id IS NULL;
UPDATE subscriptions s      SET aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = s.user_id) WHERE s.aircraft_id IS NULL;
UPDATE import_proposals p   SET aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = p.user_id) WHERE p.aircraft_id IS NULL;
UPDATE users u SET last_viewed_aircraft_id = (SELECT MIN(a.id) FROM aircraft a WHERE a.user_id = u.id)
 WHERE u.last_viewed_aircraft_id IS NULL;

-- 5. FOLLOW-UP (next release, NOT run now). Only after production's boot log
--    shows the backfill attached everything and SELECT COUNT(*) ... WHERE
--    aircraft_id IS NULL is 0 for each table:
--
-- ALTER TABLE service_timelines  ALTER COLUMN aircraft_id SET NOT NULL;
-- ALTER TABLE flight_logs        ALTER COLUMN aircraft_id SET NOT NULL;
-- ALTER TABLE flight_suggestions ALTER COLUMN aircraft_id SET NOT NULL;
-- ALTER TABLE subscriptions      ALTER COLUMN aircraft_id SET NOT NULL;
-- ALTER TABLE users DROP COLUMN block_time_hours, DROP COLUMN time_in_service_hours,
--     DROP COLUMN make_model, DROP COLUMN tail_number, DROP COLUMN owner_name, DROP COLUMN make_modelsn,
--     DROP COLUMN block_time_updated_at, DROP COLUMN time_in_service_updated_at,
--     DROP COLUMN block_time_updated_source, DROP COLUMN time_in_service_updated_source,
--     DROP COLUMN block_time_manual_baseline, DROP COLUMN time_in_service_manual_baseline;
--
-- (import_proposals.aircraft_id stays nullable: proposals outlive removed aircraft.)
-- After dropping the users columns, AircraftBackfill can be deleted.
