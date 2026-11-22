package com.example.AviaryService.services.alerts;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.example.AviaryService.services.alerts.AlertDigest.AircraftSection;
import com.example.AviaryService.services.alerts.AlertDigest.DueItem;

// Writes an alert digest as a plain-text email and an HTML email with the
// same content: only what's overdue or due soon, a line for everything on
// track, the hours, and the recipient's footer.
// Layout: specs/013-alert-email-design/contracts/alert-email.md.
@Component
public class AlertEmailRenderer {

    static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a 'UTC'").withZone(ZoneOffset.UTC);

    // Dashboard Time Left pill colors (light theme), dashboardstyle.css.
    static final String SOON_BG = "#FFF1D6", SOON_FG = "#8A5A00";
    static final String OVER_BG = "#FDE4E9", OVER_FG = "#B3203F";

    private final ITemplateEngine templateEngine;

    public AlertEmailRenderer(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    // ---- plain text ----

    // footer may be null (AlertDigest.body(): no recipient yet).
    public String text(AlertDigest d, EmailFooter footer) {
        StringBuilder sb = new StringBuilder();
        sb.append(title(d)).append(" — ").append(counts(d)).append('\n');
        sb.append("as of ").append(STAMP.format(d.asOf())).append('\n');
        if (d.baseline()) {
            sb.append('\n').append(baselineLine()).append('\n');
        }

        if (!d.isFleet()) {
            AircraftSection s = d.sections().isEmpty() ? null : d.sections().get(0);
            if (s != null) appendGroups(sb, s);
            String summary = singleSummary(s);
            sb.append('\n').append(summary.isEmpty() ? "" : summary + " ").append(attachmentLine(d)).append('\n');
            if (s != null) sb.append('\n').append(hoursLine(s)).append('\n');
        } else {
            for (AircraftSection s : d.sections()) {
                if (!s.hasDue()) continue;
                sb.append('\n').append(sectionTitle(s)).append('\n');
                appendGroups(sb, s);
                sb.append(hoursLine(s)).append('\n');
            }
            String onTrack = fleetOnTrackLine(d);
            if (onTrack != null) sb.append('\n').append(onTrack).append('\n');
            sb.append('\n').append(attachmentLine(d)).append('\n');
        }

        if (footer != null) {
            sb.append("\n----\n").append(footerSentence(footer)).append('\n');
            if (footer.hasUnsubscribe()) sb.append("Stop these emails: ").append(footer.unsubscribeUrl()).append('\n');
            if (footer.hasPostalAddress()) sb.append(footer.postalAddress()).append('\n');
        }
        return sb.toString();
    }

    private static void appendGroups(StringBuilder sb, AircraftSection s) {
        List<DueItem> over = s.due().stream().filter(DueItem::overdue).toList();
        List<DueItem> soon = s.due().stream().filter(i -> !i.overdue()).toList();
        if (!over.isEmpty()) appendGroup(sb, "OVERDUE", over);
        if (!soon.isEmpty()) appendGroup(sb, "DUE SOON", soon);
    }

    private static void appendGroup(StringBuilder sb, String label, List<DueItem> items) {
        sb.append('\n').append(label).append('\n');
        for (DueItem i : items) {
            sb.append("  • ").append(i.name());
            if (i.timeText() != null && !i.timeText().isBlank()) sb.append(" — ").append(i.timeText());
            if (i.isNew()) sb.append("  [new]");
            sb.append('\n');
        }
    }

    // ---- HTML ----

    public String html(AlertDigest d, EmailFooter footer) {
        Context ctx = new Context();
        ctx.setVariable("title", title(d));
        ctx.setVariable("counts", counts(d));
        ctx.setVariable("asOf", STAMP.format(d.asOf()));
        ctx.setVariable("baselineLine", d.baseline() ? baselineLine() : null);
        ctx.setVariable("fleet", d.isFleet());

        List<Map<String, Object>> sections = new ArrayList<>();
        for (AircraftSection s : d.sections()) {
            if (d.isFleet() && !s.hasDue()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", d.isFleet() ? sectionTitle(s) : null);
            m.put("groups", groups(s));
            m.put("hours", hoursLine(s));
            sections.add(m);
        }
        ctx.setVariable("sections", sections);

        AircraftSection single = !d.isFleet() && !d.sections().isEmpty() ? d.sections().get(0) : null;
        ctx.setVariable("summary", d.isFleet() ? fleetOnTrackLine(d) : singleSummary(single));
        ctx.setVariable("attachment", attachmentLine(d));
        ctx.setVariable("footerSentence", footer == null ? null : footerSentence(footer));
        ctx.setVariable("unsubscribeUrl", footer != null && footer.hasUnsubscribe() ? footer.unsubscribeUrl() : null);
        ctx.setVariable("postalAddress", footer != null && footer.hasPostalAddress() ? footer.postalAddress() : null);
        return templateEngine.process("email/alert-digest", ctx);
    }

    private static List<Map<String, Object>> groups(AircraftSection s) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DueItem> over = s.due().stream().filter(DueItem::overdue).toList();
        List<DueItem> soon = s.due().stream().filter(i -> !i.overdue()).toList();
        if (!over.isEmpty()) out.add(group("Overdue", OVER_FG, OVER_BG, over));
        if (!soon.isEmpty()) out.add(group("Due soon", SOON_FG, SOON_BG, soon));
        return out;
    }

    private static Map<String, Object> group(String label, String fg, String bg, List<DueItem> items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("label", label);
        g.put("fg", fg);
        g.put("bg", bg);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DueItem i : items) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", i.name());
            r.put("time", i.timeText() == null || i.timeText().isBlank() ? label : i.timeText());
            r.put("isNew", i.isNew());
            rows.add(r);
        }
        g.put("items", rows);
        return g;
    }

    // ---- shared wording ----

    static String title(AlertDigest d) {
        if (d.isFleet()) return "Your fleet";
        if (d.sections().isEmpty()) return "Your aircraft";
        return sectionTitle(d.sections().get(0));
    }

    static String sectionTitle(AircraftSection s) {
        return s.makeModel() == null || s.makeModel().isBlank() ? s.tail() : s.tail() + " (" + s.makeModel() + ")";
    }

    static String counts(AlertDigest d) {
        List<String> parts = new ArrayList<>();
        if (d.overdueCount() > 0) parts.add(d.overdueCount() + " overdue");
        if (d.dueSoonCount() > 0) parts.add(d.dueSoonCount() + " due soon");
        return parts.isEmpty() ? "all on track" : String.join(", ", parts);
    }

    static String baselineLine() {
        return "Alerts are on. From now on you'll get an email when something comes due or changes.";
    }

    static String singleSummary(AircraftSection s) {
        if (s == null) return "There are no aircraft on this account yet.";
        if (s.itemCount() == 0) return s.tail() + " has no maintenance items yet.";
        if (!s.hasDue()) {
            return s.itemCount() == 1 ? "Its 1 item is on track." : "All " + s.itemCount() + " items are on track.";
        }
        if (s.onTrackCount() == 0) return "";
        return s.onTrackCount() == 1 ? "The other item is on track."
            : "The other " + s.onTrackCount() + " items are on track.";
    }

    // "N2BB and N3CC: all items on track." -- null when every aircraft has something due.
    static String fleetOnTrackLine(AlertDigest d) {
        List<String> tails = d.sections().stream().filter(s -> !s.hasDue()).map(AircraftSection::tail).toList();
        if (tails.isEmpty()) return null;
        return joinAnd(tails) + ": all items on track.";
    }

    static String attachmentLine(AlertDigest d) {
        return d.isFleet() ? "The full Status Sheets are attached (PDF)." : "The full Status Sheet is attached (PDF).";
    }

    static String hoursLine(AircraftSection s) {
        return "Hours: Hobbs Time " + s.hobbsTime() + " · Time in Service " + s.timeInService();
    }

    static String footerSentence(EmailFooter f) {
        return "You're receiving this because " + f.operator() + " added you to maintenance alerts for "
            + f.tails() + ".";
    }

    static String joinAnd(List<String> items) {
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
