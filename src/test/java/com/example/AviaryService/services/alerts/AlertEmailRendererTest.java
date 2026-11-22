package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.example.AviaryService.services.alerts.AlertDigest.AircraftSection;
import com.example.AviaryService.services.alerts.AlertDigest.DueItem;

// Spec 013: the alert email lists only what needs attention, in text and HTML.
// See specs/013-alert-email-design/contracts/alert-email.md. Also writes
// sample emails to build/alert-preview/ to look at in a browser.
class AlertEmailRendererTest {

    private static final Instant AS_OF = Instant.parse("2026-11-21T12:00:00Z");
    private static final EmailFooter FOOTER = new EmailFooter("Jane Doe", "N12SAS",
        "https://aviary.example/alerts/unsubscribe?token=abc", "123 Main St, Miami FL");

    private final AlertEmailRenderer renderer = new AlertEmailRenderer(engine());

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        return e;
    }

    private static DueItem soon(String name, String time, boolean isNew) {
        return new DueItem(name, AlertLevel.DUE_SOON, time, isNew, 0.3);
    }

    private static DueItem over(String name, String time) {
        return new DueItem(name, AlertLevel.OVERDUE, time, false, -0.4);
    }

    private static AircraftSection section(String tail, String makeModel, List<DueItem> due, int itemCount) {
        return new AircraftSection(tail, makeModel, "865.4", "679.8", due, itemCount - due.size(), itemCount);
    }

    private static AlertDigest digest(boolean baseline, AircraftSection... sections) {
        int o = 0, s = 0;
        for (AircraftSection sec : sections) {
            for (DueItem i : sec.due()) {
                if (i.overdue()) o++; else s++;
            }
        }
        return new AlertDigest(true, "subject", o, s, AS_OF, baseline, List.of(sections), LeadTimes.DEFAULT);
    }

    private static AlertDigest reported() {
        return digest(false, section("N12SAS", "Cirrus SR22T", List.of(soon("ELT Battery", "10 days left", true)), 39));
    }

    private static long bulletLines(String text) {
        return text.lines().filter(l -> l.startsWith("  • ")).count();
    }

    // ---- text (US1) ----

    @Test
    void reportedExampleListsOnlyTheDueItem() {
        String t = renderer.text(reported(), FOOTER);

        assertTrue(t.startsWith("N12SAS (Cirrus SR22T) — 1 due soon\nas of Nov 21, 2026 12:00 PM UTC\n"), t);
        assertEquals(1, bulletLines(t), t);
        assertTrue(t.contains("DUE SOON\n  • ELT Battery — 10 days left  [new]\n"), t);
        assertTrue(t.contains("The other 38 items are on track. The full Status Sheet is attached (PDF)."), t);
        assertTrue(t.contains("Hours: Hobbs Time 865.4 · Time in Service 679.8"), t);
        assertFalse(Pattern.compile("\\bok\\b").matcher(t).find(), t);
        assertFalse(t.contains("Full status"), t);
    }

    @Test
    void overdueComesBeforeDueSoon() {
        String t = renderer.text(digest(false, section("N12SAS", "",
            List.of(over("Annual", "12 days overdue"), soon("Oil", "4.5 hrs left", false)), 5)), null);
        assertTrue(t.indexOf("OVERDUE\n  • Annual") < t.indexOf("DUE SOON\n  • Oil"), t);
        assertTrue(t.startsWith("N12SAS — 1 overdue, 1 due soon\n"), t);
    }

    @Test
    void nothingDueSaysAllOnTrack() {
        String t = renderer.text(digest(false, section("N12SAS", "", List.of(), 39)), FOOTER);
        assertTrue(t.contains("All 39 items are on track. The full Status Sheet is attached (PDF)."), t);
        assertTrue(t.startsWith("N12SAS — all on track\n"), t);
        assertFalse(t.contains("OVERDUE") || t.contains("DUE SOON"), t);
        assertEquals(0, bulletLines(t));
    }

    @Test
    void onlyNewItemsAreMarked() {
        String t = renderer.text(digest(false, section("N12SAS", "",
            List.of(soon("A", "3 days left", true), soon("B", "5 days left", false)), 2)), null);
        assertTrue(t.contains("  • A — 3 days left  [new]\n"), t);
        assertTrue(t.contains("  • B — 5 days left\n"), t);
    }

    @Test
    void baselineSaysAlertsAreOn() {
        String t = renderer.text(digest(true, section("N12SAS", "", List.of(), 3)), null);
        assertTrue(t.contains("Alerts are on. From now on you'll get an email when something comes due or changes."), t);
    }

    @Test
    void footerComesLast() {
        String t = renderer.text(reported(), FOOTER);
        int footerAt = t.indexOf("\n----\n");
        assertTrue(footerAt > t.indexOf("Hours:"), t);
        assertTrue(t.substring(footerAt).contains(
            "You're receiving this because Jane Doe added you to maintenance alerts for N12SAS."), t);
        assertTrue(t.contains("Stop these emails: https://aviary.example/alerts/unsubscribe?token=abc"), t);
        assertTrue(t.contains("123 Main St, Miami FL"), t);
        assertFalse(renderer.text(reported(), null).contains("----"));
    }

    @Test
    void oneOtherItemIsSingular() {
        String t = renderer.text(digest(false, section("N12SAS", "", List.of(soon("A", "3 days left", false)), 2)), null);
        assertTrue(t.contains("The other item is on track."), t);
    }

    // ---- HTML (US2) ----

    @Test
    void htmlUsesDashboardColorsAndEscapesNames() {
        AlertDigest d = digest(false, section("N12SAS", "Cirrus SR22T",
            List.of(over("<b>x</b>", "3 days overdue"), soon("ELT Battery", "10 days left", true)), 39));
        String h = renderer.html(d, FOOTER);

        assertTrue(h.contains("background:#FFF1D6; color:#8A5A00;"), h);
        assertTrue(h.contains("background:#FDE4E9; color:#B3203F;"), h);
        assertTrue(h.contains("&lt;b&gt;x&lt;/b&gt;"), h);
        assertFalse(h.contains("<b>x</b>"), h);
        assertTrue(h.contains("href=\"https://aviary.example/alerts/unsubscribe?token=abc\""), h);
        assertEquals(1, h.split(">New<", -1).length - 1, "one New tag");
        assertTrue(h.contains("The other 37 items are on track."), h);
        assertFalse(h.contains("<img") || h.contains("<link"), h);
        assertTrue(h.contains("max-width:600px"), h);
    }

    // ---- fleet (US4) ----

    private static AlertDigest fleet() {
        return digest(false,
            section("N1AA", "Cessna 172", List.of(over("Annual", "12 days overdue"), soon("Oil change", "4.5 hrs left", false)), 10),
            section("N2BB", "Piper PA-28", List.of(), 8),
            section("N3CC", "", List.of(), 5));
    }

    @Test
    void fleetShowsOnlyAircraftWithSomethingDue() {
        String t = renderer.text(fleet(), FOOTER);
        assertTrue(t.startsWith("Your fleet — 1 overdue, 1 due soon\n"), t);
        assertTrue(t.contains("\nN1AA (Cessna 172)\n"), t);
        assertFalse(t.contains("N2BB (Piper"), t);
        assertTrue(t.contains("N2BB and N3CC: all items on track."), t);
        assertTrue(t.contains("The full Status Sheets are attached (PDF)."), t);
        assertEquals(1, t.split("Hours:", -1).length - 1, t);

        String h = renderer.html(fleet(), FOOTER);
        assertTrue(h.contains("N1AA (Cessna 172)"), h);
        assertTrue(h.contains("N2BB and N3CC: all items on track."), h);
    }

    @Test
    void joinAnd() {
        assertEquals("A", AlertEmailRenderer.joinAnd(List.of("A")));
        assertEquals("A and B", AlertEmailRenderer.joinAnd(List.of("A", "B")));
        assertEquals("A, B and C", AlertEmailRenderer.joinAnd(List.of("A", "B", "C")));
    }

    // Not an assertion: sample emails for quickstart.md "Preview".
    @Test
    void writePreviews() throws Exception {
        Path dir = Path.of("build", "alert-preview");
        Files.createDirectories(dir);
        List<DueItem> many = new ArrayList<>(List.of(
            over("Annual", "12 days overdue"),
            soon("ELT Battery", "10 days left", true),
            soon("Alternator # 1 Brushes", "8.2 hrs left", false)));
        AlertDigest single = digest(false, section("N12SAS", "Cirrus SR22T", many, 39));
        Files.writeString(dir.resolve("single.html"), renderer.html(single, FOOTER));
        Files.writeString(dir.resolve("single.txt"), renderer.text(single, FOOTER));
        Files.writeString(dir.resolve("fleet.html"), renderer.html(fleet(), FOOTER));
        Files.writeString(dir.resolve("fleet.txt"), renderer.text(fleet(), FOOTER));
        assertTrue(Files.size(dir.resolve("single.html")) > 0);
    }
}
