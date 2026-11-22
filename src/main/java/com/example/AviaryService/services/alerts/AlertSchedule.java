package com.example.AviaryService.services.alerts;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

// When an alert email goes out (spec 014). Pure: the caller gathers what's
// due and when each kind of email was last sent; this says why to send today,
// or returns an empty set for "send nothing".
// Rules: specs/014-alert-reminder-frequency/contracts/schedule-rules.md.
public final class AlertSchedule {

    private AlertSchedule() {}

    public enum Reason { NEW_DUE_SOON, NEW_OVERDUE, DUE_SOON_REMINDER, OVERDUE_REMINDER, STATUS_REPORT }

    // Frequencies use Frequency's encoding. Dates are the owner's local dates;
    // a last-sent date is null when that email has never gone out.
    public record Input(int dueSoonEvery, int overdueEvery, int statusEvery,
                        boolean anyDueSoon, boolean anyOverdue, boolean newDueSoon, boolean newOverdue,
                        LocalDate today, LocalDate lastDueSoonSent, LocalDate lastOverdueSent,
                        LocalDate lastStatusSent) {}

    public static Set<Reason> decide(Input in) {
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        // 1, 2: something got worse since the last email (Once counts here too).
        if (in.newDueSoon() && in.dueSoonEvery() != Frequency.OFF) reasons.add(Reason.NEW_DUE_SOON);
        if (in.newOverdue() && in.overdueEvery() != Frequency.OFF) reasons.add(Reason.NEW_OVERDUE);
        // 3, 4: still due soon / overdue, and that reminder's interval has passed.
        if (in.anyDueSoon() && intervalPassed(in.dueSoonEvery(), in.lastDueSoonSent(), in.today())
                && !reasons.contains(Reason.NEW_DUE_SOON)) {
            reasons.add(Reason.DUE_SOON_REMINDER);
        }
        if (in.anyOverdue() && intervalPassed(in.overdueEvery(), in.lastOverdueSent(), in.today())
                && !reasons.contains(Reason.NEW_OVERDUE)) {
            reasons.add(Reason.OVERDUE_REMINDER);
        }
        // 5: scheduled status report, whatever is due.
        if (intervalPassed(in.statusEvery(), in.lastStatusSent(), in.today())) reasons.add(Reason.STATUS_REPORT);
        return reasons;
    }

    // "Every N days": N local days after the last one (Mon -> next Mon for 7).
    // Never sent counts as passed. Off and Once never repeat.
    static boolean intervalPassed(int every, LocalDate last, LocalDate today) {
        if (every <= 0) return false;
        return last == null || !today.isBefore(last.plusDays(every));
    }

    // How often an email goes out, stored as one int:
    //   0 = Off, -1 = Once, 1..90 = every N days.
    // The API speaks "off", "once" or a number, so the sentinels stay here.
    public static final class Frequency {
        public static final int OFF = 0;
        public static final int ONCE = -1;
        public static final int MAX_DAYS = 90;

        private Frequency() {}

        public static int parse(Object v, boolean allowOnce) {
            if (v instanceof Number n) return clamp(n.intValue());
            if (v instanceof String s) {
                String t = s.trim().toLowerCase();
                if (t.equals("off")) return OFF;
                if (t.equals("once")) {
                    if (!allowOnce) throw new IllegalArgumentException("The status report can be Off or every N days.");
                    return ONCE;
                }
                try {
                    return clamp(Integer.parseInt(t));
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
            throw new IllegalArgumentException("Choose Off, Once, or a number of days from 1 to " + MAX_DAYS + ".");
        }

        public static Object toJson(int v) {
            if (v == OFF) return "off";
            if (v == ONCE) return "once";
            return v;
        }

        private static int clamp(int days) {
            return Math.max(1, Math.min(MAX_DAYS, days));
        }
    }
}
