package com.example.AviaryService.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.alerts.AlertDigest;
import com.example.AviaryService.services.alerts.AlertDigestService;
import com.example.AviaryService.services.alerts.AlertRecipientService;
import com.example.AviaryService.services.alerts.AlertService;

// Exercises the alert services against real H2. Email/SMS senders are
// unconfigured in the test profile, so NotificationService no-ops the actual
// delivery -- everything else (state transitions, digest evaluation, rate-limit
// bookkeeping) is real.
@SpringBootTest
@ActiveProfiles("test")
@Transactional // each test rolls back -- keeps alert_* rows from leaking into other test classes
class AlertServiceIntegrationTest {

    @Autowired AlertService alertService;
    @Autowired AlertRecipientService recipientService;
    @Autowired AlertDigestService digestService;
    @Autowired AlertPreferenceRepository preferenceRepository;
    @Autowired AlertRecipientRepository recipientRepository;
    @Autowired AlertSendLogRepository sendLogRepository;
    @Autowired ServiceTimelineRepository serviceTimelineRepository;
    @Autowired UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        // No deleteAll -- the @Transactional rollback isolates each test, and
        // wiping shared tables here would trip FK constraints against rows other
        // test classes own. Every query below is scoped to this fresh user.
        user = new User();
        user.setUsername("alertuser-" + System.nanoTime());
        user.setPassword("x");
        user.setTailNumber("N12345");
        user.setTimeInServiceHours(1200.0);
        userRepository.save(user);
    }

    private ServiceTimeline item(String name, String dueDate, String dueHours) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(user);
        t.setItem(name);
        t.setIsTitle(false);
        t.setTimelineOrder(1);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        return serviceTimelineRepository.save(t);
    }

    // -- recipients --

    @Test
    void addRecipient_startsPending_thenConfirms() {
        AlertRecipient r = recipientService.add(user, AlertRecipient.Channel.EMAIL, "shop@example.com", "Jane's shop");
        assertEquals(AlertRecipient.Status.PENDING, r.getStatusEnum());
        assertFalse(r.getConfirmToken().isBlank());

        assertTrue(recipientService.confirm(r.getConfirmToken()));
        assertEquals(AlertRecipient.Status.ACCEPTED,
            recipientRepository.findById(r.getId()).orElseThrow().getStatusEnum());
    }

    @Test
    void confirm_withBadToken_isFalse() {
        assertFalse(recipientService.confirm("nope-not-a-real-token"));
    }

    @Test
    void addRecipient_rejectsBadEmail() {
        assertThrows(IllegalArgumentException.class,
            () -> recipientService.add(user, AlertRecipient.Channel.EMAIL, "not-an-email", null));
    }

    @Test
    void addRecipient_duplicateActive_throws_butReinvitesDeclined() {
        AlertRecipient r = recipientService.add(user, AlertRecipient.Channel.EMAIL, "dup@example.com", null);
        assertThrows(IllegalArgumentException.class,
            () -> recipientService.add(user, AlertRecipient.Channel.EMAIL, "dup@example.com", null));

        recipientService.decline(r.getConfirmToken());
        AlertRecipient reinvited = recipientService.add(user, AlertRecipient.Channel.EMAIL, "dup@example.com", null);
        assertEquals(AlertRecipient.Status.PENDING, reinvited.getStatusEnum());
    }

    @Test
    void resend_mintsNewToken() {
        AlertRecipient r = recipientService.add(user, AlertRecipient.Channel.EMAIL, "resend@example.com", null);
        String first = r.getConfirmToken();
        AlertRecipient after = recipientService.resend(user, r.getId());
        assertNotEquals(first, after.getConfirmToken());
        assertEquals(AlertRecipient.Status.PENDING, after.getStatusEnum());
    }

    // -- digest evaluation --

    @Test
    void digest_countsOverdueAndDueSoon() {
        item("Annual", "2026-09-01", null);        // overdue (today = server today)
        item("Transponder", null, null);           // ok
        item("Oil change", null, "1205");          // 5 hrs left -> due soon (<=10)

        AlertPreference prefs = alertService.getOrCreatePrefs(user);
        AlertDigest d = digestService.buildDigest(user, prefs, false);

        assertEquals(1, d.overdueCount());
        assertEquals(1, d.dueSoonCount());
        assertTrue(d.subject().contains("N12345"));
        assertTrue(d.body().contains("Annual"));
    }

    @Test
    void digest_shouldSendOnlyWhenSomethingChanged() {
        item("Annual", "2026-09-01", null); // overdue

        AlertPreference prefs = alertService.getOrCreatePrefs(user);

        AlertDigest first = digestService.buildDigest(user, prefs, false);
        assertTrue(first.shouldSend(), "first evaluation of an overdue item should fire");

        digestService.markFired(user, prefs);

        AlertDigest second = digestService.buildDigest(user, prefs, false);
        assertFalse(second.shouldSend(), "nothing changed since markFired -> no send");
    }

    @Test
    void baselineDigest_alwaysSends_evenWithNothingDue() {
        item("Future thing", "2027-01-01", null); // ok

        AlertPreference prefs = alertService.getOrCreatePrefs(user);
        AlertDigest d = digestService.buildDigest(user, prefs, true);
        assertTrue(d.shouldSend());
        assertTrue(d.body().contains("Alerts are now ON"));
    }

    // -- enable transition + sweep --

    @Test
    void enablingAlerts_setsBaselineTimestamp_and_snapshotsLevels() {
        item("Annual", "2026-09-01", null); // overdue

        AlertPreference prefs = alertService.updatePrefs(user, true, 7, 30, 10, 7);
        assertTrue(prefs.isAlertsEnabled());
        assertTrue(preferenceRepository.findByUser(user).orElseThrow().getBaselineSentAt() != null);

        // levels snapshotted -> a same-day sweep at the check hour sends nothing
        boolean sent = alertService.runSweepForUser(user, prefs, LocalDate.now(), prefs.getCheckHour());
        assertFalse(sent);
    }

    @Test
    void sweep_isNoOpWhenDisabledOrWrongHour() {
        item("Annual", "2026-09-01", null);
        AlertPreference prefs = alertService.getOrCreatePrefs(user); // disabled by default
        assertFalse(alertService.runSweepForUser(user, prefs, LocalDate.now(), prefs.getCheckHour()));

        prefs.setAlertsEnabled(true);
        preferenceRepository.save(prefs);
        int wrongHour = (prefs.getCheckHour() + 1) % 24;
        assertFalse(alertService.runSweepForUser(user, prefs, LocalDate.now(), wrongHour));
    }
}
