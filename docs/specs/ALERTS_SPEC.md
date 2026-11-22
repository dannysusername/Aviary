# Maintenance Alerts Spec

Status: **Backend + email built (Amazon SES); SMS = model + logging sender only; UI = functional** · Last updated: 2026-10-18

Alerts the aircraft owner (and anyone they add — a maintenance shop, a partner)
when a Status Sheet item is near or past its due date. Email goes through
Amazon SES (`SesEmailService`). SMS is stubbed (`LoggingSmsSender`) until a real
provider is wired up.

Separate from the AeroAPI flight-sync `Subscription` — that means "poll AeroAPI
for this tail". This is a different concern with its own entities.

## How it fires

- `AlertScheduler` ticks **every hour at :05** (FlightSyncService runs at :00).
  A user is evaluated only when alerts are on, the current hour matches their
  `checkHour`, and they haven't been checked today (`lastCheckedOn`). So: at most
  one check per user per day.
- `checkHour` is in the **owner's time zone** (`AlertPreference.timeZone`, sent by
  the dashboard from the browser; `America/New_York` until known). `lastCheckedOn`
  is the owner's local date (spec 014).
- On a check, every non-title item on the Status Sheet gets a **level**:
  `OK` → `DUE_SOON` → `OVERDUE`.
  - `OVERDUE`  — `daysLeft < 0` or `hoursLeft < 0`
  - `DUE_SOON` — `0 ≤ daysLeft ≤ leadTimeDays` or `0 ≤ hoursLeft ≤ leadTimeHours`
  - else `OK`
  - An item has two limits (calendar and flight hours); the worse one wins.
- Each item stores the level it **last alerted at** (`StatusSheetItem.alertLevel`)
  and when (`alertLastFiredAt`).
- **Three kinds of alert email, each with its own frequency** (spec 014), set in
  Settings → Alert schedule. Each is Off, Once (not the status report), or every
  N days (1–90). Defaults: **Due soon every 7 days, Overdue every 3 days, Status
  report Off.** `AlertSchedule.decide` (pure, unit-tested) says to send when:
  1. an item became `DUE_SOON` since the last email (Due soon not Off), or
  2. an item became `OVERDUE` since the last email (Overdue not Off), or
  3. something is due soon and N days passed since the last email that listed
     due-soon items (`lastDueSoonSentAt`), or
  4. something is overdue and N days passed since `lastOverdueSentAt`, or
  5. the status report is on and N days passed since `lastStatusSentAt`.
  Several reasons on one day → one email. "N days" compares the owner's local
  dates. A status report with nothing due has the subject
  `N12345 — weekly status: all on track`.
- The old "re-nudge overdue every N days" (`overdueRenudgeDays`) is now the
  Overdue frequency; existing values carry over.
- When an item improves (maintenance done, due date pushed out), its
  `alertLevel` is quietly reset down. No "good news" email.
- No per-edit sending.

## Turning alerts on

The alerts section on the dashboard has an **on/off checkbox**, schedule
settings, and a readiness line. Backed by `PUT /alerts/preferences`.

- **Readiness line:** "Before turning on: …" lists what's missing, from
  `GET /alerts/preferences` → `readiness.missing`:
  - "Add at least one maintenance item"
  - "Set your tail number"
  - "Set your operator name"
  - "Enter aircraft hours"
  Advisory only; the server does not block turning alerts on.
- **Every time** `enabled` flips false→true, the server snapshots each item's
  current level (so the next check doesn't flood) and sends **one baseline
  digest** ("Alerts are now ON. This is your starting picture…").

## Recipients (adding people)

`AlertRecipient` — belongs to a user. One user → up to **10** recipients.

| field | notes |
|---|---|
| `channel` | `EMAIL` \| `SMS` |
| `destination` | email address or E.164 phone (`+15551234567`) |
| `label` | optional free text ("Jane's shop"), max 100 chars |
| `status` | `PENDING` \| `ACCEPTED` \| `DECLINED` \| `EXPIRED` |
| `confirmToken` | random URL-safe token, unique |
| `createdAt`, `confirmedAt`, `reminderSentAt` | timestamps |

Flow: **add → PENDING** → confirm email sent ("[operator name] added you to receive
maintenance alerts for [tail]", with Confirm and Decline links) → recipient
confirms (`ACCEPTED`) or declines (`DECLINED`). Only `ACCEPTED` recipients get
digests.

Rules on add (`AlertRecipientService.add`), checked in this order:

1. Address is valid (not blank, ≤ 254 chars, email or E.164 format); label ≤ 100.
2. Owner has an **operator name** and **tail number** (`requireProfile`). Also
   checked on Resend. If either is cleared later, emails fall back to
   "The aircraft owner" / "your aircraft" (`AlertText`).
3. Same channel + address already on the list:
   - `DECLINED` / `EXPIRED` → re-invited (new token, back to `PENDING`, new email).
   - `PENDING` / `ACCEPTED` → refused ("That address is already on the list").
4. Fewer than 10 recipients.

Errors come back as `400 {"error": "[message]"}`; the dashboard shows the
message under the form and as a toast.

- Confirm link **expires after 14 days**; a reminder goes out at **day 7**
  (`reminderSentAt`). After expiry the row shows `EXPIRED` and the owner presses
  Resend for a fresh token. An expired token can't be confirmed.
- **Consent:** the owner can't consent for someone else; every recipient
  confirms for themselves. SMS will need double opt-in + STOP/HELP when it's real.
- The owner is *not* auto-added. They add their own email like anyone else.

## Rate limits

**Digests** (daily check, baseline, and "Send alert now" share one budget, per
recipient, tracked in `AlertSendLog`):

- **Email:** max 1 per recipient per **calendar day in the owner's time zone**
  (was a rolling 24 h, which dropped the next day's email whenever the hourly
  check ran a few seconds earlier; spec 014 R4)
- **SMS:** max 1 per recipient per 72h

On cooldown the send is skipped for that recipient (logged, not an error). The
"Send alert now" result shows how many were actually sent ("Sent to 0
recipient(s)." when everyone is on cooldown). No client-side countdown.

**Confirm emails and owner-triggered emails** (add, resend, Email PDF):
`OutboundEmailLimiter`, max **10 per owner per hour**. Over the limit → 429 and
nothing is saved.

## Digest content

- **Subject:** `N12345 — 1 overdue, 2 due soon` (or `N12345 — maintenance status`).
- **Body (spec 013):** sent as **text + HTML** (same content; `AlertEmailRenderer`,
  template `templates/email/alert-digest.html`, inline styles only). Heading
  `[tail] ([make/model]) — [counts]` + "as of" time → baseline line (first email
  only) → **only items that are overdue or due soon** (owner's lead times), grouped
  `OVERDUE` then `DUE SOON`, most urgent first (days/leadDays vs hours/leadHours,
  the smaller wins), `[new]` / "New" on items that got worse since the last email →
  "The other N items are on track." (or "All N items are on track.") → hours
  (Hobbs Time / Time in Service). Items that are fine are not listed; they're in the PDF.
  Fleet: one section per aircraft with something due, then "N2BB and N3CC: all items
  on track."
- **Attachment:** Status Sheet PDF for the fleet (`PdfExportService.generateFleetPdf`),
  built once per digest. Time Left is labeled `OVERDUE` (red `#B3203F`) or
  `DUE SOON` (amber `#8A5A00`) using the owner's lead times, the same rule as the
  dashboard pills. The PDF downloaded or shared from the dashboard uses the same rule.
- **Footer:** "You're receiving this because [operator name] added you to maintenance
  alerts for [tail]." + unsubscribe link (per recipient)
  (`/alerts/unsubscribe?token=…`) + postal address if configured (CAN-SPAM).
  **No dashboard link:** recipients have no login.
- **SMS:** one short line. No attachment.

## Thresholds

Per-user defaults in `AlertPreference`: `leadTimeDays` (30), `leadTimeHours`
(10). They decide what "due soon" means; how often each email goes out is the
three frequencies above. Per-item overrides are a later addition.

## Entities

- **`AlertPreference`** — `@OneToOne` User. `alertsEnabled` (false),
  `checkHour` (7, clamped 0–23), `leadTimeDays` (30), `leadTimeHours` (10),
  `overdueRenudgeDays` = Overdue frequency (3 for new rows; 0 Off, -1 Once,
  1–90), `dueSoonEveryDays` (7), `statusEveryDays` (0 = Off), `timeZone` (IANA,
  null → America/New_York), `lastDueSoonSentAt` / `lastOverdueSentAt` /
  `lastStatusSentAt` (Instant, null), `lastCheckedOn` (owner-local LocalDate,
  null), `baselineSentAt` (Instant, null). Migration note:
  `docs/migrations/2026-11-21_alert_schedules.sql`.
- **`AlertRecipient`** — as table above.
- **`StatusSheetItem`** has `alertLevel` (String, nullable) and
  `alertLastFiredAt` (Instant, nullable).
- **`AlertSendLog`** — `recipientId`, `channel`, `sentAt`. Backs the digest rate
  limit. Rows older than 30 days are pruned.

## Housekeeping (every scheduler tick)

After the per-user checks, each hourly tick also:

- sends day-7 reminders and marks 14-day-old `PENDING` rows `EXPIRED`
  (`housekeepPendingRecipients`)
- prunes `AlertSendLog` rows older than 30 days (`pruneSendLog`)

## Endpoints

Login required (owner), under `/alerts` (`AlertController`, JSON):

| method | path | body / params | purpose |
|---|---|---|---|
| GET  | `/alerts/preferences` | — | current prefs (+ `dueSoonEvery`, `overdueEvery`, `statusEvery` as `"off"`/`"once"`/N, `timeZone`) + readiness |
| PUT  | `/alerts/preferences` | JSON `enabled,checkHour,leadTimeDays,leadTimeHours,dueSoonEvery,overdueEvery,statusEvery` (old `overdueRenudgeDays` still accepted) | update; false→true sends baseline; bad frequency → 400 |
| POST | `/alerts/time-zone` | JSON `timeZone` (IANA id) | sets the owner's zone; invalid → 400 |
| GET  | `/alerts/recipients` | — | list with statuses |
| POST | `/alerts/recipients` | form `channel,destination,label` | add → PENDING, sends confirmation |
| DELETE | `/alerts/recipients/{id}` | — | remove |
| POST | `/alerts/recipients/{id}/resend` | — | new token + resend confirmation |
| POST | `/alerts/send-now` | — | send current status to ACCEPTED recipients now → `{"sent": N}` (respects cooldown) |

Public, no login (`AlertPublicController`, HTML; `permitAll` and CSRF-exempt in
`SecurityConfig`; the token is the proof):

| method | path | purpose |
|---|---|---|
| GET  | `/alerts/confirm?token=…`     | page with a "Yes, send me alerts" button |
| POST | `/alerts/confirm`             | PENDING → ACCEPTED |
| GET  | `/alerts/decline?token=…`     | page with a "No thanks" button |
| POST | `/alerts/decline`             | → DECLINED |
| GET  | `/alerts/unsubscribe?token=…` | page with an "Unsubscribe" button |
| POST | `/alerts/unsubscribe`         | → DECLINED (same as decline; separate URL for email footers) |

GET only shows a page; the POST button does the change. Email security scanners
open every link, so a GET that acted would sign people up or unsubscribe them by
accident.

## Config (env vars; app-level, not per-user)

```
AWS_ACCESS_KEY_ID             # IAM user with SES send permission
AWS_SECRET_ACCESS_KEY
AWS_REGION                    # us-east-2 (where the SES identity lives)
AVIARY_SES_FROM               # sender on the verified domain, e.g. aviary-alerts@danielibarra.com
AVIARY_SES_FROM_NAME          # default "Aviary"
AVIARY_ALERTS_BASE_URL        # https://aviary.danielibarra.com on Heroku; http://localhost:8080 locally
AVIARY_ALERTS_POSTAL_ADDRESS  # CAN-SPAM footer
```

Locally these come from `.env`; on Heroku from Config Vars. When the AWS keys
or `AVIARY_SES_FROM` are blank, `SesEmailService.isConfigured()` is false and
`NotificationService` logs and skips email instead of failing. SMS always logs
and skips (`LoggingSmsSender`).

SES account is in the sandbox (verified recipients only, 200/day) until
production access is approved.

## Not built yet

- Real SMS send + inbound STOP webhook.
- Per-item threshold overrides.
- HTML (vs plain-text) email body.
- View-only dashboard link for recipients (they get the PDF instead).
- Day-7 reminder still uses its own wording: doesn't name the owner and falls
  back to "an aircraft" instead of using `AlertText`.
