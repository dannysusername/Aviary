package com.example.AviaryService.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.AviaryService.util.Validation;

// Turns every account that existed before fleets into an account with one
// aircraft. Runs on every boot, after Hibernate (ddl-auto=update) has added
// the aircraft table and the nullable aircraft_id columns, and before any
// seeder or scheduled job touches the data.
//
//   1. Drops the old "one subscription per user" unique constraint, which
//      ddl-auto=update never removes and which would block a second
//      aircraft's subscription.
//   2. For each user with no aircraft: creates one from the legacy users
//      columns (info card, both hours, both baselines, updated-at/source),
//      then attaches that user's timeline rows, flights, suggestions,
//      subscription and import proposals, and sets last_viewed_aircraft_id.
//
// Idempotent: users who already have an aircraft are skipped and only NULL
// aircraft_id values are filled, so re-running changes nothing. Never
// deletes or overwrites data. The legacy users columns are left in place.
// See specs/003-multi-aircraft-fleet/research.md R3 and
// docs/migrations/2026-11-09_multi_aircraft.sql.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AircraftBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AircraftBackfill.class);

    private static final String[] LEGACY_COLUMNS = {
        "tail_number", "make_model", "owner_name", "make_modelsn",
        "block_time_hours", "time_in_service_hours",
        "block_time_manual_baseline", "time_in_service_manual_baseline",
        "block_time_updated_at", "time_in_service_updated_at",
        "block_time_updated_source", "time_in_service_updated_source",
    };

    private static final String[] CHILD_TABLES = {
        "service_timelines", "flight_logs", "flight_suggestions", "subscriptions", "import_proposals",
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AircraftBackfill(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        dropOldSubscriptionConstraint();
        Result r = backfill();
        log.info("Aircraft backfill: {} users, {} rows attached", r.users(), r.rows());
    }

    public record Result(int users, int rows) {}

    public Result backfill() {
        Set<String> legacy = legacyColumns();
        List<Long> userIds = jdbc.queryForList(
            "SELECT u.id FROM users u WHERE NOT EXISTS (SELECT 1 FROM aircraft a WHERE a.user_id = u.id)",
            Long.class);
        int users = 0;
        int rows = 0;
        for (Long userId : userIds) {
            try {
                Integer attached = transaction.execute(tx -> backfillUser(userId, legacy));
                users++;
                rows += attached == null ? 0 : attached;
            } catch (RuntimeException e) {
                // One bad row must not stop everyone else; the dashboard
                // creates an empty aircraft for anyone left without one.
                log.error("Aircraft backfill failed for user {}", userId, e);
            }
        }
        return new Result(users, rows);
    }

    private int backfillUser(Long userId, Set<String> legacy) {
        Map<String, Object> old = legacy.isEmpty()
            ? Map.of()
            : jdbc.queryForMap("SELECT " + String.join(", ", legacy) + " FROM users WHERE id = ?", userId);

        String tail = Validation.normalizeTailNumber(str(old.get("tail_number")));
        jdbc.update("""
            INSERT INTO aircraft (user_id, tail_number, tail_number_key, make_model, owner_name, make_modelsn,
                                  block_time_hours, time_in_service_hours,
                                  block_time_manual_baseline, time_in_service_manual_baseline,
                                  block_time_updated_at, time_in_service_updated_at,
                                  block_time_updated_source, time_in_service_updated_source, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            userId, tail, Validation.tailNumberKey(tail),
            str(old.get("make_model")), str(old.get("owner_name")), str(old.get("make_modelsn")),
            num(old.get("block_time_hours"), 0.0), num(old.get("time_in_service_hours"), 0.0),
            num(old.get("block_time_manual_baseline"), null), num(old.get("time_in_service_manual_baseline"), null),
            old.get("block_time_updated_at"), old.get("time_in_service_updated_at"),
            str(old.get("block_time_updated_source")), str(old.get("time_in_service_updated_source")),
            Timestamp.from(Instant.now()));

        Long aircraftId = jdbc.queryForObject(
            "SELECT MIN(id) FROM aircraft WHERE user_id = ?", Long.class, userId);

        int attached = 0;
        for (String table : CHILD_TABLES) {
            attached += jdbc.update("UPDATE " + table + " SET aircraft_id = ? WHERE user_id = ? AND aircraft_id IS NULL",
                aircraftId, userId);
        }
        jdbc.update("UPDATE users SET last_viewed_aircraft_id = ? WHERE id = ? AND last_viewed_aircraft_id IS NULL",
            aircraftId, userId);
        return attached;
    }

    // Which legacy aircraft columns still exist on users. A fresh database
    // (created after this change) has none of them.
    private Set<String> legacyColumns() {
        SqlRowSet probe = jdbc.queryForRowSet("SELECT * FROM users WHERE 1 = 0");
        SqlRowSetMetaData meta = probe.getMetaData();
        Set<String> present = new HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            present.add(meta.getColumnName(i).toLowerCase());
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String c : LEGACY_COLUMNS) {
            if (present.contains(c)) out.add(c);
        }
        return out;
    }

    // Any unique constraint on subscriptions that covers user_id alone. Its
    // name is Hibernate-generated (or uq_subscriptions_user from the
    // 2026-09-05 migration), so look it up instead of guessing. Works on
    // PostgreSQL and H2 (both have information_schema).
    void dropOldSubscriptionConstraint() {
        List<String> names;
        try {
            names = jdbc.queryForList("""
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage k
                  ON k.constraint_name = tc.constraint_name AND k.table_name = tc.table_name
                WHERE LOWER(tc.table_name) = 'subscriptions' AND tc.constraint_type = 'UNIQUE'
                GROUP BY tc.constraint_name
                HAVING COUNT(*) = 1 AND MAX(LOWER(k.column_name)) = 'user_id'""", String.class);
        } catch (RuntimeException e) {
            log.warn("Could not look up subscriptions constraints: {}", e.getMessage());
            return;
        }
        for (String name : names) {
            try {
                jdbc.execute("ALTER TABLE subscriptions DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
                log.info("Dropped one-subscription-per-user constraint {}", name);
            } catch (RuntimeException e) {
                log.warn("Could not drop constraint {} on subscriptions: {}", name, e.getMessage());
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double num(Object o, Double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }
}
