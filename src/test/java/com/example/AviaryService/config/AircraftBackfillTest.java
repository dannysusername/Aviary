package com.example.AviaryService.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.SubscriptionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// US2 / FR-015: an account from before fleets (everything on the users row,
// aircraft_id NULL everywhere) comes out of the backfill as one aircraft with
// every value and row attached, and a second run changes nothing.
// Not @Transactional: the backfill commits per user in its own transactions.
@SpringBootTest
@ActiveProfiles("test")
class AircraftBackfillTest {

    @Autowired AircraftBackfill backfill;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ServiceTimelineRepository timelineRepository;
    @Autowired FlightLogRepository flightLogRepository;
    @Autowired FlightSuggestionRepository suggestionRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired ImportProposalRepository proposalRepository;

    private static final String[] LEGACY_DDL = {
        "tail_number VARCHAR(255)", "make_model VARCHAR(255)", "owner_name VARCHAR(255)", "make_modelsn VARCHAR(255)",
        "block_time_hours DOUBLE PRECISION", "time_in_service_hours DOUBLE PRECISION",
        "block_time_manual_baseline DOUBLE PRECISION", "time_in_service_manual_baseline DOUBLE PRECISION",
        "block_time_updated_at TIMESTAMP(6) WITH TIME ZONE", "time_in_service_updated_at TIMESTAMP(6) WITH TIME ZONE",
        "block_time_updated_source VARCHAR(255)", "time_in_service_updated_source VARCHAR(255)",
    };

    @BeforeEach
    void legacyColumns() {
        // A database from before fleets still has these columns on users.
        for (String col : LEGACY_DDL) {
            jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS " + col);
        }
    }

    // A pre-fleet user: legacy columns filled, children with no aircraft.
    private User legacyUser() {
        User u = new User();
        u.setUsername("legacy-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        jdbc.update("""
            UPDATE users SET tail_number = ?, make_model = ?, owner_name = ?, make_modelsn = ?,
                block_time_hours = ?, time_in_service_hours = ?,
                block_time_manual_baseline = ?, time_in_service_manual_baseline = ?,
                block_time_updated_at = ?, time_in_service_updated_at = ?,
                block_time_updated_source = ?, time_in_service_updated_source = ?
            WHERE id = ?""",
            "N482DM", "Cirrus SR22", "Pat Owner", "3517",
            1532.4, 1418.7, 1500.0, 1400.0,
            Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")), Timestamp.from(Instant.parse("2026-10-02T12:00:00Z")),
            "manual", "flightlog", u.getId());

        // Non-sequential order with a title row, to prove order survives.
        timelineRepository.save(row(u, "Engine", true, 7));
        timelineRepository.save(row(u, "Oil Change", false, 3));
        timelineRepository.save(row(u, "Annual", false, 11));

        for (String from : List.of("KTMB", "KFLL")) {
            FlightLog f = new FlightLog();
            f.setUser(u);
            f.setFromAirport(from);
            flightLogRepository.save(f);
        }
        Instant now = Instant.now();
        suggestionRepository.save(new FlightSuggestion(u, "N482DM", "fa-" + System.nanoTime(),
            now.minusSeconds(7200), now.minusSeconds(3600), "KTMB", "KORL"));
        subscriptionRepository.save(new Subscription(u, "N482DM"));

        ImportProposal p = new ImportProposal();
        p.setUser(u);
        p.setStatus(ImportProposal.OPEN);
        p.setItemsJson("[]");
        p.setRejectedJson("[]");
        p.setCreatedAt(now);
        p.setExpiresAt(now.plusSeconds(3600));
        proposalRepository.save(p);
        return u;
    }

    private static ServiceTimeline row(User u, String item, boolean title, int order) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem(item);
        t.setIsTitle(title);
        t.setTimelineOrder(order);
        return t;
    }

    private long nullAircraftRows(String table, User u) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ? AND aircraft_id IS NULL",
            Long.class, u.getId());
    }

    @Test
    void legacyAccountBecomesOneAircraftWithEverythingAttached() {
        User u = legacyUser();

        backfill.backfill();

        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(u);
        assertEquals(1, fleet.size());
        Aircraft a = fleet.get(0);
        assertEquals("N482DM", a.getTailNumber());
        assertEquals("N482DM", a.getTailNumberKey());
        assertEquals("Cirrus SR22", a.getMakeModel());
        assertEquals("Pat Owner", a.getOwnerName());
        assertEquals("3517", a.getMakeModelSN());
        assertEquals(1532.4, a.getBlockTimeHours(), 1e-9);
        assertEquals(1418.7, a.getTimeInServiceHours(), 1e-9);
        assertEquals(1500.0, a.getBlockTimeManualBaseline(), 1e-9);
        assertEquals(1400.0, a.getTimeInServiceManualBaseline(), 1e-9);
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), a.getBlockTimeUpdatedAt());
        assertEquals(Instant.parse("2026-10-02T12:00:00Z"), a.getTimeInServiceUpdatedAt());
        assertEquals("manual", a.getBlockTimeUpdatedSource());
        assertEquals("flightlog", a.getTimeInServiceUpdatedSource());

        for (String table : List.of("service_timelines", "flight_logs", "flight_suggestions", "subscriptions", "import_proposals")) {
            assertEquals(0, nullAircraftRows(table, u), table + " rows left without an aircraft");
        }

        // Same rows, same order (SC-002).
        List<ServiceTimeline> rows = timelineRepository.findByAircraftOrderByTimelineOrderAsc(a);
        assertEquals(List.of("Oil Change", "Engine", "Annual"), rows.stream().map(ServiceTimeline::getItem).toList());
        assertEquals(List.of(3, 7, 11), rows.stream().map(ServiceTimeline::getTimelineOrder).toList());
        assertEquals(2, flightLogRepository.findByAircraft(a).size());

        Long lastViewed = jdbc.queryForObject("SELECT last_viewed_aircraft_id FROM users WHERE id = ?", Long.class, u.getId());
        assertEquals(a.getId(), lastViewed);
    }

    @Test
    void runningAgainChangesNothing() {
        User u = legacyUser();
        backfill.backfill();
        Aircraft first = aircraftRepository.findByUserOrderByIdAsc(u).get(0);
        List<Map<String, Object>> before = jdbc.queryForList(
            "SELECT id, aircraft_id, timeline_order FROM service_timelines WHERE user_id = ? ORDER BY id", u.getId());

        backfill.backfill();

        assertEquals(1, aircraftRepository.findByUserOrderByIdAsc(u).size());
        assertEquals(first.getId(), aircraftRepository.findByUserOrderByIdAsc(u).get(0).getId());
        assertEquals(before, jdbc.queryForList(
            "SELECT id, aircraft_id, timeline_order FROM service_timelines WHERE user_id = ? ORDER BY id", u.getId()));
    }

    @Test
    void userWhoAlreadyHasAnAircraftIsSkipped() {
        User u = new User();
        u.setUsername("modern-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        Aircraft existing = TestFleet.addAircraft(aircraftRepository, userRepository, u, "N1AB", "Me", 1, 2);

        backfill.backfill();

        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(u);
        assertEquals(1, fleet.size());
        assertEquals(existing.getId(), fleet.get(0).getId());
        assertEquals("N1AB", fleet.get(0).getTailNumber());
    }

    @Test
    void blankLegacyTailGivesUnnamedAircraft() {
        User u = new User();
        u.setUsername("blank-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);

        backfill.backfill();

        Aircraft a = aircraftRepository.findByUserOrderByIdAsc(u).get(0);
        assertNull(a.getTailNumberKey());
        assertEquals("Unnamed aircraft", a.getDisplayName());
        assertNotNull(a.getTimeInServiceHours());
    }

    // T028: the old one-subscription-per-user rule is gone, so each aircraft
    // can have its own subscription.
    @Test
    void secondAircraftCanHaveItsOwnSubscription() {
        // Recreate the 2026-09-05 constraint as production has it, then drop it.
        jdbc.execute("ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS uq_subscriptions_user");
        User u = new User();
        u.setUsername("subs-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        Aircraft a = TestFleet.addAircraft(aircraftRepository, userRepository, u, "N1AA", "Me", 0, 0);
        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, u, "N2BB", "Me", 0, 0);

        jdbc.execute("ALTER TABLE subscriptions ADD CONSTRAINT uq_subscriptions_user UNIQUE (user_id)");
        backfill.dropOldSubscriptionConstraint();

        Subscription sa = new Subscription(u, "N1AA");
        sa.setAircraft(a);
        subscriptionRepository.save(sa);
        Subscription sb = new Subscription(u, "N2BB");
        sb.setAircraft(b);
        subscriptionRepository.save(sb);

        assertEquals(sa.getId(), subscriptionRepository.findByAircraft(a).orElseThrow().getId());
        assertEquals(sb.getId(), subscriptionRepository.findByAircraft(b).orElseThrow().getId());
    }
}
