# Maintenance Alerts Spec

Status: **Backend + email built (senders disabled until keys added); SMS = model + logging sender only; UI = functional** · Last updated: 2026-09-08

Alerts the aircraft owner (and anyone they add — a maintenance shop, a partner)
when a Service Timeline item is near or past its due date. Email via SendGrid,
SMS via Twilio (SMS send path stubbed until A2P 10DLC clears).

Separate from the AeroAPI flight-sync `Subscription` — that means "poll AeroAPI
for this tail". This is a different concern with its own entities.

## How it fires

- **One check per user per day**, at an hour the user picks (`checkHour`, same
  pattern as `Subscription.preferredCheckHour`). A `@Scheduled` job ticks
  hourly; a user is evaluated only on the tick where `checkHour` matches and
  `alertsEnabled` is true.
- On a tick it looks at the user's whole Service Timeline and classifies each
  non-title item into a **level**: `OK` → `DUE_SOON` → `OVERDUE`.
  - `OVERDUE`  — `daysLeft < 0` or `hoursLeft < 0`
  - `DUE_SOON` — `0 ≤ daysLeft ≤ leadTimeDays` or `0 ≤ hoursLeft ≤ leadTimeHours`
  - else `OK`
- Each item stores the level it **last alerted at** (`ServiceTimeline.alertLevel`)
  and when (`alertLastFiredAt`).
- A digest is sent only if **either**:
  1. some item moved to a worse level than it last alerted at, **or**
  2. some item is still `OVERDUE` and `alertLastFiredAt` is ≥ `overdueRenudgeDays`
     ago (weekly re-nudge; user-configurable).
- When an item improves (maintenance completed, due date pushed out), its
  `alertLevel` is quietly reset down — no "good news" email.
- No per-edit sending. No background safety logic beyond the daily sweep.

## Turning alerts on

Two-step dialog (frontend), backed by `PUT /alerts/preferences {enabled:true}`:

1. **"What's still missing"** — the client shows unfinished setup (no
   maintenance rows / aircraft hours not set / schedule not saved). Skip to
   step 2 if nothing's missing. Advisory only; the server does not block.
2. **Recipient list with statuses** → "Turn on alerts".

First time `enabled` flips false→true: the server marks every current item's
`alertLevel` to its present level (so you don't get a flood) and sends **one
baseline digest** ("alerts are on, here's where everything stands").

## Recipients (adding people)

`AlertRecipient` — belongs to a user. One user → many recipients.

| field | notes |
|---|---|
| `channel` | `EMAIL` \| `SMS` |
| `destination` | email address or E.164 phone |
| `label` | optional free text ("Jane's shop") |
| `status` | `PENDING` \| `ACCEPTED` \| `DECLINED` \| `EXPIRED` |
| `confirmToken` | opaque URL-safe token, unique |
| `createdAt`, `confirmedAt`, `reminderSentAt` | timestamps |

Flow: **add → PENDING** → confirmation sent (email: a confirm link; SMS: "reply
YES", stubbed) → recipient clicks **confirm** (`ACCEPTED`) or **decline**
(`DECLINED`). Only `ACCEPTED` recipients receive anything.

- Confirm link (and PENDING row) **expires after 14 days**; a reminder goes out
  at **day 7** (`reminderSentAt`). After expiry the row shows `EXPIRED` and the
  owner presses Resend to mint a fresh token.
- **Consent basis:** user A cannot consent for user B. Email confirmation
  protects sender reputation (CAN-SPAM); SMS needs double opt-in + STOP/HELP
  (TCPA) — handled by a Twilio Messaging Service + inbound webhook when SMS is
  finished.
- The owner's own alerts: the owner is *not* auto-added as a recipient. They add
  their own email like anyone else (keeps one code path, and makes the "who gets
  this" list complete and honest).

## Rate limit

Manual ("Send alert now") and the daily digest **share one budget**:

- **Email:** max 1 per recipient per 24h
- **SMS:** max 1 per recipient per 72h

On cooldown the send is skipped for that recipient (logged, not an error) and
the button greys out client-side with "Available again in Xh".

## Digest content

- **Subject:** `N12345 — 1 overdue, 2 due soon`
- **Body (email):** "what changed" section → full status table, worst first →
  aircraft hours → generated-at timestamp → *View Dashboard* button → the
  dashboard **PDF attached** (`PdfExportService.generateDashboardPdf`) → a
  manage/unsubscribe link. Non-owner recipients also see tail number, make/model,
  and who added them.
- **SMS:** one short line + a link. No attachment.
- Every email includes an unsubscribe link (`/alerts/unsubscribe?token=…`) and
  the configured postal address (`aviary.alerts.postal-address`), for CAN-SPAM.

## Thresholds

One per-user default in `AlertPreference`: `leadTimeDays` (30), `leadTimeHours`
(10). Per-item overrides are a later addition.

## Entities

- **`AlertPreference`** — `@OneToOne` User. `alertsEnabled` (false),
  `checkHour` (7), `leadTimeDays` (30), `leadTimeHours` (10),
  `overdueRenudgeDays` (7), `lastCheckedOn` (LocalDate, null),
  `baselineSentAt` (Instant, null).
- **`AlertRecipient`** — as table above.
- **`ServiceTimeline`** gains `alertLevel` (String, nullable) and
  `alertLastFiredAt` (Instant, nullable). `ddl-auto=update` adds the columns.
- **`AlertSendLog`** — `recipientId`, `channel`, `sentAt`. Backs the rate limit
  and the activity trail. Pruned after 30 days by the daily job.

## Endpoints

Authenticated (owner), under `/alerts`:

| method | path | body / params | purpose |
|---|---|---|---|
| GET  | `/alerts/preferences` | — | current prefs + readiness flags |
| PUT  | `/alerts/preferences` | `enabled,checkHour,leadTimeDays,leadTimeHours,overdueRenudgeDays` | update; false→true triggers baseline |
| GET  | `/alerts/recipients` | — | list with statuses |
| POST | `/alerts/recipients` | `channel,destination,label` | add → PENDING, sends confirmation |
| DELETE | `/alerts/recipients/{id}` | — | remove |
| POST | `/alerts/recipients/{id}/resend` | — | new token + resend confirmation |
| POST | `/alerts/send-now` | — | evaluate + send current status to ACCEPTED recipients (respects rate limit) |

Public (no auth — `permitAll` in `SecurityConfig`), token-guarded:

| method | path | purpose |
|---|---|---|
| GET | `/alerts/confirm?token=…`     | PENDING → ACCEPTED |
| GET | `/alerts/decline?token=…`     | PENDING/ACCEPTED → DECLINED |
| GET | `/alerts/unsubscribe?token=…` | ACCEPTED → DECLINED (same as decline; distinct URL for email footers) |

## Config (env vars; app-level, not per-user)

```
SENDGRID_API_KEY            # sendgrid.env / Heroku config var
AVIARY_SENDGRID_FROM        # verified sender address
AVIARY_SENDGRID_FROM_NAME   # default "Aviary Service"
AVIARY_ALERTS_BASE_URL      # e.g. https://aviarist-xxxx.herokuapp.com  (for links)
AVIARY_ALERTS_POSTAL_ADDRESS# CAN-SPAM footer
TWILIO_ACCOUNT_SID          # SMS — later
TWILIO_AUTH_TOKEN           # SMS — later
TWILIO_MESSAGING_SERVICE_SID# SMS — later
```

When `SENDGRID_API_KEY` / `AVIARY_SENDGRID_FROM` are blank the email sender
logs and no-ops instead of throwing. SMS sender currently always logs-and-no-ops
(`LoggingSmsSender`); `TwilioSmsSender` is a drop-in for later.

## Not built yet

- Real Twilio SMS send + inbound STOP webhook + A2P 10DLC.
- Per-item threshold overrides.
- HTML (vs plain-text) email body — currently plain text + PDF attachment.
- The day-7 reminder is sent by the daily job; there is no separate finer-grained
  scheduler.
