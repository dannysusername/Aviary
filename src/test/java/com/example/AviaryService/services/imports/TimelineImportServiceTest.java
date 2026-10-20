package com.example.AviaryService.services.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.TimelineImportRecordRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.dashboard.TimelineService;
import com.example.AviaryService.services.imports.TimelineImportService.ImportResult;
import com.example.AviaryService.util.TimelineImportRules.InputItem;
import com.example.AviaryService.util.TimelineImportRules.RejectedRow;

// Propose / continue / confirm against real H2. Not @Transactional: confirm
// runs its own transaction and the all-or-nothing test needs to see what it
// actually committed. Every test uses fresh users, so leftover rows don't
// interfere.
@SpringBootTest
@ActiveProfiles("test")
class TimelineImportServiceTest {

    @Autowired TimelineImportService importService;
    @Autowired TimelineService timelineService;
    @Autowired ImportProposalRepository proposalRepository;
    @Autowired ServiceTimelineRepository timelineRepository;
    @Autowired TimelineImportRecordRepository recordRepository;
    @Autowired UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = saveUser("import-alice-");
        bob = saveUser("import-bob-");
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        u.setTimeInServiceHours(1000.0);
        return userRepository.save(u);
    }

    private ServiceTimeline existing(User owner, String name, int order) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setItem(name);
        t.setTimelineOrder(order);
        t.setLastDoneDate("2026-01-01");
        return timelineRepository.save(t);
    }

    private static InputItem calendarItem(String name, String lastDone, String months) {
        return new InputItem(name, null, null, lastDone, null, months, "MONTHS", null, null, null, null, name + " row", null);
    }

    private static InputItem hoursItem(String name, String lastHours, String cycle) {
        return new InputItem(name, null, null, null, lastHours, null, null, cycle, null, null, null, name + " row", null);
    }

    private static InputItem junk(String text) {
        return new InputItem(null, null, null, null, null, null, null, null, null, null, null, text, null);
    }

    private List<ServiceTimeline> rows(User u) {
        return timelineRepository.findByUserOrderByTimelineOrderAsc(u);
    }

    // ---- propose (US1) ----

    @Test
    void proposeLeavesTimelineUnchanged() {
        existing(alice, "Annual", 0);
        ImportResult r = importService.propose(alice,
            List.of(calendarItem("ELT", "2026-01-10", "12"), hoursItem("Oil", "1000", "50")), null, null);
        assertEquals("PROPOSED", r.status());
        assertNotNull(r.proposalId());
        assertEquals(2, r.items().size());
        assertEquals(1, rows(alice).size());
    }

    @Test
    void secondProposalReplacesFirst() {
        ImportResult first = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")), null, null);
        ImportResult second = importService.propose(alice, List.of(hoursItem("Oil", "1000", "25")), null, null);
        assertEquals(ImportProposal.REPLACED, proposalRepository.findById(first.proposalId()).orElseThrow().getStatus());
        assertEquals(ImportProposal.OPEN, proposalRepository.findById(second.proposalId()).orElseThrow().getStatus());
        assertEquals("REPLACED", importService.confirm(alice, first.proposalId(), null).status());
    }

    @Test
    void proposalExpiresAfter24Hours() {
        ImportResult r = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")), null, null);
        ImportProposal p = proposalRepository.findById(r.proposalId()).orElseThrow();
        assertEquals(TimelineImportService.PROPOSAL_TTL, java.time.Duration.between(p.getCreatedAt(), p.getExpiresAt()));

        p.setExpiresAt(Instant.now().minusSeconds(1));
        proposalRepository.save(p);
        assertEquals("EXPIRED", importService.confirm(alice, r.proposalId(), null).status());
        assertEquals(0, rows(alice).size());
    }

    @Test
    void proposalRateLimit() {
        for (int i = 0; i < TimelineImportService.PROPOSALS_PER_HOUR; i++) {
            assertEquals("PROPOSED", importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")), null, null).status());
        }
        ImportResult limited = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")), null, null);
        assertEquals("RATE_LIMITED", limited.status());
        assertNotNull(limited.retryAfterSeconds());
        // Per owner: bob is unaffected.
        assertEquals("PROPOSED", importService.propose(bob, List.of(hoursItem("Oil", "1000", "50")), null, null).status());
    }

    @Test
    void otherOwnerCannotUseProposal() {
        ImportResult r = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")), null, null);
        assertEquals("NOT_FOUND", importService.confirm(bob, r.proposalId(), null).status());
        assertEquals("NOT_FOUND", importService.continuePoorFit(bob, r.proposalId()).status());
        assertEquals(0, rows(bob).size());
        assertEquals(ImportProposal.OPEN, proposalRepository.findById(r.proposalId()).orElseThrow().getStatus());
    }

    @Test
    void sourceRejectsAppearInProposal() {
        ImportResult r = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50")),
            List.of(new RejectedRow("smudged line", "Unreadable")), null);
        assertEquals("PROPOSED", r.status());
        assertTrue(r.rejected().stream().anyMatch(x -> x.sourceRow().equals("smudged line")));
    }

    // ---- confirm (US2) ----

    @Test
    void confirmAddsExactlyTheProposalAfterExistingRows() {
        existing(alice, "Annual", 0);
        existing(alice, "Transponder", 1);
        ImportResult p = importService.propose(alice,
            List.of(new InputItem("Engine", null, true, null, null, null, null, null, null, null, null, "ENGINE", null),
                    hoursItem("Oil", "1,000", "50"),
                    calendarItem("ELT", "2026-01-10", "12")), null, "https://assistant.example.com/oauth/client.json");

        ImportResult r = importService.confirm(alice, p.proposalId(), null);
        assertEquals("IMPORTED", r.status(), r.message());
        assertEquals(3, r.addedCount());

        List<ServiceTimeline> all = rows(alice);
        assertEquals(List.of("Annual", "Transponder", "Engine", "Oil", "ELT"),
            all.stream().map(ServiceTimeline::getItem).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), all.stream().map(ServiceTimeline::getTimelineOrder).toList());
        assertEquals("2026-01-01", all.get(0).getLastDoneDate()); // existing row untouched

        ServiceTimeline oil = all.get(3);
        assertEquals("1000", oil.getLastDoneHours());
        assertEquals("1050", oil.getDueDateHours());
        assertEquals(50.0, oil.getCycleHours());
        ServiceTimeline elt = all.get(4);
        assertEquals("2027-01-10", elt.getDueDateDate());
        assertTrue(all.get(2).getIsTitle());

        assertEquals(1, recordRepository.findTop20ByUserOrderByImportedAtDesc(alice).size());
        assertEquals(3, recordRepository.findTop20ByUserOrderByImportedAtDesc(alice).get(0).getItemCount());
        assertEquals("ALREADY_CONFIRMED", importService.confirm(alice, p.proposalId(), null).status());
    }

    @Test
    void duplicatesSkippedUnlessOptedIn() {
        existing(alice, "Oil Change", 0);
        existing(alice, "Annual", 1);
        ImportResult p = importService.propose(alice,
            List.of(hoursItem("oil change", "1000", "50"), calendarItem("annual", "2026-02-01", "12"),
                    hoursItem("Mags", "1000", "500")), null, null);
        int annualIndex = p.items().stream().filter(i -> i.item().equals("annual")).findFirst().orElseThrow().index();

        ImportResult r = importService.confirm(alice, p.proposalId(), Set.of(annualIndex));
        assertEquals(2, r.addedCount());
        assertEquals(List.of("oil change"), r.skippedDuplicates());
        assertEquals(List.of("Oil Change", "Annual", "annual", "Mags"),
            rows(alice).stream().map(ServiceTimeline::getItem).toList());
    }

    @Test
    void matchAddedAfterProposalIsSkippedAndReported() {
        ImportResult p = importService.propose(alice, List.of(hoursItem("Oil", "1000", "50"), hoursItem("Mags", "1000", "500")), null, null);
        existing(alice, "OIL", 0);
        ImportResult r = importService.confirm(alice, p.proposalId(), null);
        assertEquals(1, r.addedCount());
        assertEquals(List.of("Oil"), r.newDuplicates());
    }

    @Test
    void failedConfirmSavesNothing() {
        ImportResult p = importService.propose(alice,
            List.of(hoursItem("One", "1", "5"), hoursItem("Two", "1", "5"), hoursItem("Three", "1", "5")), null, null);
        // Corrupt item 3 in storage so its insert fails (name too long for the column).
        ImportProposal stored = proposalRepository.findById(p.proposalId()).orElseThrow();
        stored.setItemsJson(stored.getItemsJson().replace("\"Three\"", "\"" + "x".repeat(400) + "\""));
        proposalRepository.save(stored);

        ImportResult r = importService.confirm(alice, p.proposalId(), null);
        assertEquals("FAILED", r.status());
        assertEquals(0, rows(alice).size());
        assertEquals(ImportProposal.OPEN, proposalRepository.findById(p.proposalId()).orElseThrow().getStatus());
        assertEquals(0, recordRepository.findTop20ByUserOrderByImportedAtDesc(alice).size());
    }

    @Test
    void confirmRateLimit() {
        for (int i = 0; i < TimelineImportService.CONFIRMS_PER_HOUR; i++) {
            importService.confirm(alice, -1L, null);
        }
        assertEquals("RATE_LIMITED", importService.confirm(alice, -1L, null).status());
    }

    @Test
    void importedItemWorksWithCompleteMaintenance() {
        ImportResult p = importService.propose(alice, List.of(hoursItem("Oil", "900", "50")), null, null);
        importService.confirm(alice, p.proposalId(), null);
        ServiceTimeline oil = rows(alice).get(0);
        TimelineService.CompletedMaintenance done = timelineService.completeMaintenance(alice, oil.getId());
        assertEquals("1000", done.lastDone());
        assertEquals("1050", done.dueDate());
    }

    @Test
    void onConditionItemIsImportedAndLabeled() {
        ImportResult p = importService.propose(alice, List.of(
            new InputItem("Flexible Fluid Hose", "Replace", null, null, null, null, null, null, null, null, null, "hose row", true),
            new InputItem("Brake Pads", null, null, null, null, null, null, null, null, null, null, "pads row", true)),
            null, null);
        assertEquals("PROPOSED", p.status(), p.message());
        assertEquals("IMPORTED", importService.confirm(alice, p.proposalId(), null).status());
        List<ServiceTimeline> all = rows(alice);
        assertEquals("Replace (on condition)", all.get(0).getDescription());
        assertEquals("On condition", all.get(1).getDescription());
        assertNull(all.get(0).getDueDateDate());
    }

    @Test
    void onConditionDescriptionRules() {
        assertEquals("On condition", TimelineImportService.onConditionDescription(null));
        assertEquals("Replace On Condition", TimelineImportService.onConditionDescription("Replace On Condition"));
        String long255 = "x".repeat(250);
        assertEquals(long255, TimelineImportService.onConditionDescription(long255));
    }

    // ---- refusals (US3) ----

    @Test
    void unrelatedInputCreatesNoProposal() {
        long before = proposalRepository.count();
        ImportResult r = importService.propose(alice, List.of(junk("Banana bread: 3 bananas"), junk("Bake 350F 1 hr")), null, null);
        assertEquals("REJECTED", r.status());
        assertNull(r.proposalId());
        assertNotNull(r.example());
        assertEquals(2, r.rejected().size());
        assertEquals(before, proposalRepository.count());
    }

    @Test
    void tooLargeCreatesNoProposal() {
        long before = proposalRepository.count();
        List<InputItem> many = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(hoursItem("Item " + i, "1", "5"));
        assertEquals("TOO_LARGE", importService.propose(alice, many, null, null).status());
        assertEquals(before, proposalRepository.count());
    }

    @Test
    void poorFitNeedsContinueBeforeConfirm() {
        ImportResult p = importService.propose(alice,
            List.of(hoursItem("Oil", "1000", "50"), junk("??"), junk("###")), null, null);
        assertEquals("POOR_FIT", p.status());
        assertEquals(ImportProposal.NEEDS_FIT_CONFIRMATION,
            proposalRepository.findById(p.proposalId()).orElseThrow().getStatus());

        assertEquals("NEEDS_FIT_CONFIRMATION", importService.confirm(alice, p.proposalId(), null).status());
        assertEquals(0, rows(alice).size());

        assertEquals("PROPOSED", importService.continuePoorFit(alice, p.proposalId()).status());
        assertEquals("IMPORTED", importService.confirm(alice, p.proposalId(), null).status());
        assertEquals(1, rows(alice).size());
    }
}
