# Maintenance Alerts Spec

Status: **Backend + email built (Amazon SES); SMS = model + logging sender only; UI = functional** · Last updated: 2026-10-18

Alerts the aircraft owner (and anyone they add — a maintenance shop, a partner)
when a Service Timeline item is near or past its due date. Email goes through
Amazon SES (`SesEmailService`). SMS is stubbed (`LoggingSmsSender`) until a real
provider is wired up.

Separate from the AeroAPI flight-sync `Subscription` — that means "poll AeroAPI
for this tail". This is a different concern with its own entities.

## How it fires

- `AlertScheduler` ticks **every hour at :05** (FlightSyncService runs at :00).
  A user is evaluated only when alerts are on, the current hour matches their
  `checkHour`, and they haven't been checked today (`lastCheckedOn`). So: at most
  one check per user per day.
- `checkHour` is in the **server's time zone** (`ZoneId.systemDefault()`). On
  Heroku that is UTC.
- On a check, every non-title item on the Service Timeline gets a **level**:
  `OK` → `DUE_SOON` → `OVERDUE`.
  - `OVERDUE`  — `daysLeft < 0` or `hoursLeft < 0`
  - `DUE_SOON` — `0 ≤ daysLeft ≤ leadTimeDays` or `0 ≤ hoursLeft ≤ leadTimeHours`
  - else `OK`
  - An item has two limits (calendar and flight hours); the worse one wins.
- Each item stores the level it **last alerted at** (`ServiceTimeline.alertLevel`)
  and when (`alertLastFiredAt`).
- A digest is sent only if **either**:
  1. some item moved to a worse level than it last alerted at, **or**
  2. some item is still `OVERDUE` and `alertLastFiredAt` is ≥ `overdueRenudgeDays`
     ago (re-nudge; default 7, user-configurable).
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
  - "Set your owner name"
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

Flow: **add → PENDING** → confirm email sent ("[owner name] added you to receive
maintenance alerts for [tail]", with Confirm and Decline links) → recipient
confirms (`ACCEPTED`) or declines (`DECLINED`). Only `ACCEPTED` recipients get
digests.

Rules on add (`AlertRecipientService.add`), checked in this order:

1. Address is valid (not blank, ≤ 254 chars, email or E.164 format); label ≤ 100.
2. Owner has an **owner name** and **tail number** (`requireProfile`). Also
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

- **Email:** max 1 per recipient per 24h
- **SMS:** max 1 per recipient per 72h

On cooldown the send is skipped for that recipient (logged, not an error). The
"Send alert now" result shows how many were actually sent ("Sent to 0
recipient(s)." when everyone is on cooldown). No client-side countdown.

**Confirm emails and owner-triggered emails** (add, resend, Email PDF):
`OutboundEmailLimiter`, max **10 per owner per hour**. Over the limit → 429 and
nothing is saved.

## Digest content

- **Subject:** `N12345 — 1 overdue, 2 due soon` (or `N12345 — maintenance status`).
- **Body (plain text):** "Maintenance status for [tail] ([make/model])" + timestamp
  → baseline note or "What changed" → full status, worst first. Each line puts
  the overdue part first, e.g. `OVERDUE  Annual Inspection — 480.0 hrs overdue ·
  106 days left` → aircraft hours (Block Time / Time in Service).
- **Attachment:** dashboard PDF (`PdfExportService.generateDashboardPdf`), built
  once per digest.
- **Footer:** "You're receiving this because [owner name] added you to maintenance
  alerts for [tail]. The full dashboard is attached as a PDF." + unsubscribe link
  (`/alerts/unsubscribe?token=…`) + postal address if configured (CAN-SPAM).
  **No dashboard link:** recipients have no login.
- **SMS:** one short line. No attachment.

## Thresholds

Per-user defaults in `AlertPreference`: `leadTimeDays` (30), `leadTimeHours`
(10). Per-item overrides are a later addition.

## Entities

- **`AlertPreference`** — `@OneToOne` User. `alertsEnabled` (false),
  `checkHour` (7, clamped 0–23), `leadTimeDays` (30), `leadTimeHours` (10),
  `overdueRenudgeDays` (7, min 1), `lastCheckedOn` (LocalDate, null),
  `baselineSentAt` (Instant, null).
- **`AlertRecipient`** — as table above.
- **`ServiceTimeline`** has `alertLevel` (String, nullable) and
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
| GET  | `/alerts/preferences` | — | current prefs + readiness |
| PUT  | `/alerts/preferences` | JSON `enabled,checkHour,leadTimeDays,leadTimeHours,overdueRenudgeDays` | update; false→true sends baseline |
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
