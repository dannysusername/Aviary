# Planned Changes

Small, scoped things identified while building a feature but deliberately not
done at the time. Not a full roadmap — just a place to not lose these.

## Maintenance due-date alerts (email/SMS to owner or shop)

**BUILT 2026-09-08** — full backend, email path, and a functional Settings UI.
See `docs/ALERTS_SPEC.md`. Email is live once `SENDGRID_API_KEY` /
`AVIARY_SENDGRID_FROM` are set; SMS ships as the recipient/consent model +
`LoggingSmsSender` only (real Twilio + A2P 10DLC still to do). New: entities
`AlertPreference` / `AlertRecipient` / `AlertSendLog`, `service_timelines`
gains `alert_level` + `alert_last_fired_at`, `AlertService` /
`AlertDigestService` / `NotificationService` / `AlertScheduler`,
`/alerts/*` endpoints, migration `docs/migrations/2026-09-08_maintenance_alerts.sql`.
The notes below are the original pre-build plan, kept for context.

**What:** alert the aircraft owner or maintenance shop when a Service
Timeline item is nearing its due date/hours, instead of relying on someone
opening the dashboard to notice.

**Checked 2026-09-06: Twilio is NOT actually set up in this project.** No
Twilio dependency in `build.gradle`, no credentials in `.env`, no SMS code
anywhere. "Forgot password?" on the login page is a dead link with no
backing endpoint. `docs/SHARE_EXPORT_SPEC.md` lists Twilio as a *planned*
Phase 3 for the (also unbuilt) PDF-share-via-text feature — that's likely
what got remembered as "already set up." There is also currently no email or
phone number stored anywhere on `User`.

**What already exists to build on:**
- A `@Scheduled` background job already runs (`FlightSyncService`,
  `@EnableScheduling` already on at the app level) — the same pattern fits a
  daily alert-check job, no new scheduling infra needed.
- "Is this due soon" math already exists, split across two places:
  `UserController.computeTimeLeftString` (hours, server-side) and
  `calculateTimeLeft` in `dashboard.js` (calendar dates, client-side only).

**What's missing before this can work:**
1. A contact channel — at minimum an email field on `User` (decide: alert
   the pilot, a separate shop contact, or both). Phone number too if SMS is
   wanted later.
2. A way to actually send something — email (SendGrid, or free Gmail SMTP
   to start) is simpler than SMS (Twilio needs a paid account + phone number
   + A2P 10DLC carrier registration, a multi-day approval process).
3. A "don't repeat yourself" flag per `ServiceTimeline` row (e.g. "last
   alerted at") so a daily check doesn't re-alert every single day once an
   item crosses the threshold.
4. A due-soon threshold (e.g. 30 days / 10 hours out) — fixed default is
   fine to start.

**Suggested order:** email first (free, no approval wait, proves the whole
pipeline) → move the calendar due-date math server-side so a background job
can check every row, not just what's open in a browser → add the
already-alerted flag → Twilio/SMS as a later phase, reusing whatever account
setup the Share feature's texting phase eventually needs too.

## Airport code autocomplete for From/To fields

**What:** a typeahead dropdown under the From/To flight-log inputs that
filters a list of airports (code, name, city) as the user types, so they can
type either the code or the city and pick from matches — same pattern
already used elsewhere in this app (the Service Timeline description
dropdown), fed by airport data instead.

**Data:** the `deadhead` project has `data/airports.csv` — but it's a
worldwide dataset, and this app only needs US airports. Decided
2026-09-06 not to pull the whole world in for now.

**Next step when picked back up:** either trim that CSV down to US-only
before copying it in, or just grab US airports fresh from a free source
(OurAirports.com) sized for this app specifically.

## DONE (2026-09-05): Garmin CSV parser now extracts real timestamps

`FlightLog` gained `blockTimeStart`/`blockTimeEnd` and
`timeInServiceStart`/`timeInServiceEnd` (`departureTime`/`arrivalTime`
renamed to the latter pair for symmetry — see `docs/migrations/`). The
Garmin CSV parser now combines `Lcl Date` + `Lcl Time` + `UTCOfst` into real
UTC instants instead of discarding the date/offset after computing a
duration. Manual entries can optionally set the same four fields via new
datetime-local inputs. The flight log table now sorts chronologically by
these timestamps instead of by entry order, and `HoursService.recomputeChain`
keeps every CSV/AeroAPI row's Out/In accurate relative to real flight time
regardless of what order rows were added in — see `FlightLog.source` and
`HoursService.recomputeChain`/`checkAccuracy`.

## Possible future feature: cross-check AeroAPI suggestions against all FlightLog rows

**What:** right now, AeroAPI dedupe only compares new flights against other
AeroAPI-sourced flights (`fa_flight_id`). It does not check whether a
suggested flight overlaps something the user already entered manually or via
CSV — so AeroAPI can suggest a flight the user already logged another way,
and the user has to Dismiss it themselves.

**Why it's deferred:** matching a suggested flight against arbitrary manual/
CSV log entries (no shared ID, only approximate timestamps) is a real
matching problem, not a quick add. Decided 2026-09-04 to keep v1 simple:
AeroAPI-vs-AeroAPI dedupe only, rely on Dismiss for everything else. As of
2026-09-05 manual/CSV entries do now carry real timestamps
(`FlightLog.blockTimeStart`/`timeInServiceStart` etc. — see the item above),
so the "only approximate timestamps" blocker for CSV rows is gone; the
matching-logic complexity itself is still unbuilt.

**If ever built:** compare a new AeroAPI flight's `actual_off`/`actual_on`
window against existing `FlightLog.timeInServiceStart`/`timeInServiceEnd` for
that user, skip creating a suggestion on overlap.

## Other AeroAPI endpoints worth considering later

Checked the full spec (`aeroapi-openapi.yml`, 64 endpoints — verified
2026-09-04, only 12 were visible in an earlier pass because path keys are
YAML-quoted; correcting that turned up the rest). Not built, just noting
what's there:

- **`GET /account/usage`** — returns `total_cost`, `total_calls`,
  `total_failed_calls`, per-resource breakdown, for a date range. Directly
  useful for the still-open "will I get auto-charged past $5/month" question
  — could show the user their actual current-month spend in Settings instead
  of leaving them to guess. Updated every 10 minutes per FlightAware, not
  real-time.
- **`POST /alerts`** — AeroAPI can push a webhook to a URL we host on
  departure/arrival events, instead of us polling on a schedule. Worth real
  consideration as a replacement for the poller entirely — needs a publicly
  reachable endpoint (not available on localhost dev), which is why polling
  was the simpler starting point.
- **`GET /aircraft/{ident}/owner`** and **`GET /aircraft/{ident}/blocked`** —
  exactly what the deferred FAA-ownership check (see `ADSB_SYNC_SPEC.md`,
  "What's not built") and the LADD/PIA privacy-program dead end would use,
  whenever that gets built.
- **`GET /history/aircraft/{registration}/last_flight`** — cheap "has
  anything happened since I last checked" poll, lighter than a full
  `/flights/{ident}` call. Could replace the current call if per-call cost
  ever matters.

## Heroku Scheduler one-off entrypoint for the sweeps

**BUILT 2026-09-09** — `config/SyncRunner.java`. The Eco web dyno sleeps
after 30 min idle, so the in-process `@Scheduled` sweeps in
`FlightSyncService` (`0 0 * * * *`) and `AlertScheduler` (`0 5 * * * *`)
don't fire reliably. `SyncRunner` is an `ApplicationRunner` gated on
`@ConditionalOnProperty("app.run-sync"=true)` that runs both sweeps once
(each independently) then `System.exit`s. Heroku Scheduler runs it hourly
on a short-lived one-off dyno:

```
java -jar build/libs/AviaryService-0.0.1-SNAPSHOT.jar --app.run-sync=true --server.port=0
```

Notes:
- `app.run-sync` must be a **command-line arg, never a config var** — as a
  config var every web boot would run the sweep and exit.
- Can't pass `--spring.main.web-application-type=none`: `SecurityConfig`
  uses `MvcRequestMatcher`, which needs Spring MVC beans, so the one-off
  dyno boots the full app (Tomcat included) then exits. One-off dynos have
  no 60s boot timeout, so this is fine. `--server.port=0` avoids any port
  clash.
- Cost: ~1 min/run on Eco × hourly ≈ ~12 hrs/month from the 1000-hour Eco
  pool. The `@Scheduled` annotations are left in place as a no-op backup
  (they still won't fire while the dyno sleeps).

**Follow-up not done:** loosen `FlightSyncService.isDue()` from
"currentHour == preferredCheckHour" to "preferred hour has passed today
and hasn't run today yet", so a late or skipped hourly run doesn't drop a
user for the whole day.

## Multiple aircraft per user

**What:** a user tracks more than one airplane. Each aircraft has its own
Service Timeline, aircraft-info card, flight logs, Hobbs/Tach hours, and
AeroAPI subscription — one aircraft shown per page, with a switcher to
pick the active one. Essentially every per-user thing becomes per-aircraft.

**Current model (what has to change):** everything hangs off `User`
directly — `User.tailNumber`, `User.hobbsHours`/`tachHours`, the
`makeModel`/`ownerName`/`makeModelSN` fields, `User.aeroApiKey`; and
`ServiceTimeline`, `FlightLog`, `DescriptionOption`, `Subscription`,
`FlightSuggestion`, `AlertPreference`/`AlertRecipient` are all
`@ManyToOne User`. `Subscription.user_id` even carries a `unique = true`
("one subscription per user") — that constraint is the first thing to go.

**What's involved:**
1. New `Aircraft` entity `@ManyToOne User`, owning: makeModel, tailNumber,
   ownerName, serial, hobbsHours/tachHours + their
   `*UpdatedAt`/`*UpdatedSource` stamps. Decide: `aeroApiKey` per-aircraft,
   or keep one key on `User` shared across their planes.
2. Re-parent `ServiceTimeline`, `FlightLog`, `DescriptionOption`,
   `Subscription`, `FlightSuggestion`, `AlertPreference`/`AlertRecipient`
   from `user_id` to `aircraft_id`.
3. Move the `unique` constraint off `Subscription.user_id` and onto
   `aircraft_id` (one AeroAPI sync per plane).
4. Every repository `findByUser(...)` → `findByAircraft(...)`; every
   controller lookup switches from "the authenticated user's X" to "the
   active aircraft's X". Active aircraft id in the session or as a
   `?aircraftId=` param.
5. Dashboard gets an aircraft switcher (dropdown / tabs).
6. Registration flow creates the user + their first aircraft together, so
   the dashboard is never aircraft-less. (Ties into the default-rows item
   below — the default Service Timeline seeds the first aircraft.)
7. Migration: create `aircraft`; for each existing user insert one row
   from their current `User.*` values; backfill `aircraft_id` on every
   child table; then drop the moved columns from `users`.

**Why it's deferred:** biggest schema change in the app — touches nearly
every entity, repository, controller method, the seeder, and needs a data
migration on prod Postgres. Its own branch.

## Tier-aware AeroAPI endpoints

**What:** let users whose FlightAware plan allows it use AeroAPI endpoints
beyond the current `/flights/{ident}` + `/account/usage`. The free
"Personal" tier can't hit the historical `/history/*` endpoints (and has
shallow `/flights/*` history depth); paid tiers can, and also open up
things like `/aircraft/{ident}/owner`, `/aircraft/{ident}/blocked`,
`/foresight/*`, scheduled-flight lookups, and `POST /alerts` webhooks.

**Check the site:** FlightAware documents which endpoints each subscription
class permits, and it changes — when this is picked up, pull the current
tier→endpoint matrix from the live AeroAPI docs / pricing page rather than
baking in a stale list. Don't hard-assume the free tier.

**How it could work:**
- `/account/usage` (already the key-validity check) does not report the
  plan tier. Either (a) probe one representative gated endpoint once on
  key-connect (tiny `/history/...` range) and cache 200 vs 401/403 per
  user, or (b) let each feature call its endpoint and degrade on 402/403.
- Gate the UI: features backed by gated endpoints show locked with a
  "requires a paid AeroAPI plan" note until the probe says otherwise.
- `AeroApiClient` already funnels every call through one `get()` with
  error translation — map 402/403 there to a typed `AeroApiTierException`
  the controllers turn into a clean message.

**Why it's deferred:** needs the live tier→endpoint mapping plus a
probe/cache design; not worth it until a second endpoint is actually in use.

## Default Service Timeline rows for new users

**What:** a new account starts with a set of generic Service Timeline rows
already there — **item name, description, and cycle** (calendar or hours)
filled in, but **last-done and due-date fields left blank** for the user
to set once they know their aircraft's history.

**Content:** the common, type-agnostic recurring items every piston GA
aircraft has. Roughly the `DataSeeder` list minus the dates/hours and
minus anything model-specific:
- Annual Inspection — 12 months (FAR 91.409)
- 100-Hour Inspection — 100 hours (FAR 91.409(b))
- Oil & Filter Change — ~50 hours
- Transponder Test — 24 months (FAR 91.413)
- Pitot-Static / Altimeter Test — 24 months (FAR 91.411)
- VOR Check — 30 days (FAR 91.171, IFR only)
- ELT Inspection — 12 months; ELT Battery — per TSO-C91a
No tail- or engine-specific items (those vary by manufacturer).

**How:** on registration, insert these rows for the new user — or, once
"Multiple aircraft per user" lands, for the user's first aircraft. A small
hardcoded template list in code, separate from `DataSeeder` (which only
seeds the two demo logins).

**Open choice:** one-time at creation only — let the user clear/edit them
freely and never re-add on later logins.
