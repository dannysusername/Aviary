# AeroAPI Flight Sync Spec

Status: **Built** · Last updated: 2026-09-04

Watches a user's tail number using **their own FlightAware AeroAPI key** and
offers detected flights as log book entries instead of making the user type
them in. AeroAPI-only — no free ADS-B, both because AeroAPI already gives
finished flights (nothing left to detect) and because the AeroAPI Personal
License forbids mixing it with another real-time flight data provider.

## The two numbers this app tracks

| Field | What it is | Source |
|---|---|---|
| **Block Time** | engine-on to engine-off | manual entry only |
| **Time in Service** | wheels-off to wheels-on (the real maintenance clock) | manual entry, CSV upload, or AeroAPI |

AeroAPI's `actual_off`/`actual_on` map directly to Time in Service. It's just a
suggestion until the user presses Add — nothing writes itself.

## How it works

1. `FlightSyncService` ticks **hourly**, but the tick itself never calls
   AeroAPI — it's a cheap in-memory check per active subscription (`isDue()`):
   does the current local hour match `Subscription.preferredCheckHour`, and
   has at least `pollIntervalDays` passed since `lastCheckedAt`? Only when
   both are true does that tick actually call AeroAPI, so a subscription set
   to check every 3 days only ever spends AeroAPI usage once every 3 days,
   never hourly. Both settings are user-editable in Settings → "Check
   automatically every". Separately, the **Check flights now** button calls
   `FlightSyncService.syncNow()` directly, skipping this gate for an
   immediate one-off check.
2. It calls `AeroApiClient.getRecentFlights()`, which hits
   `GET /flights/{ident}` and returns only completed (`status == "Arrived"`)
   flights with both timestamps present.
3. Each flight is deduped on `fa_flight_id` — AeroAPI's own unique ID per leg.
   Already seen (in any state — pending, accepted, or dismissed) → skipped.
   This only compares AeroAPI flights against other AeroAPI flights, not
   against manually-entered or CSV-imported log book rows — see `CHANGES.md`
   for that as a possible future feature.
4. New flights are saved as `FlightSuggestion` rows, `pending`.
5. They show up as a banner on the Log Book tab. **Add** creates a real
   `FlightLog` row (airports + departure/arrival time; Block Time is left
   blank and Time in Service is filled in from the flight's airborne
   duration) and flips the suggestion to
   `accepted`. **Dismiss** flips it to `dismissed`. Both are permanent — an
   accepted or dismissed flight is never suggested again.

## What's built

- `FlightSuggestion` entity + repository
- `AeroApiClient` — the AeroAPI HTTP calls (`getRecentFlights`, `getUsage`)
- `FlightSyncService` — hourly-ticking, interval-gated poller
  (`syncDueSubscriptions`/`isDue`, only calls AeroAPI once due) and the
  on-demand path (`syncNow`), sharing the same sync logic
- `FlightSuggestionService` — accept/dismiss
- Controller endpoints: `POST /flightsuggestions/{id}/accept`, `/dismiss`,
  `POST /subscription/check-now`, `GET /aeroapi/usage`
- Dashboard Settings panel: AeroAPI key + usage display (validates the key on
  save), Subscribe/Unsubscribe, Check flights now
- Log Book tab: pending-flights banner, Add/Dismiss buttons
- `Subscription.toggle()` requires an AeroAPI key before it'll turn on

## What's not built

- FAA-registry ownership check before allowing a subscription (anyone could
  currently subscribe to any tail number)
- The two open AeroAPI license/billing questions from the earlier draft of
  this doc are unresolved and intentionally not blocking further work right
  now

## Rules that still apply

1. Propose, never write — nothing touches the log book without Add.
2. Hours only go up, never down.
3. AeroAPI being slow/down never hangs the dashboard — one bad subscription
   is caught and logged, the rest of the sync run continues.
4. Only completed (`Arrived`) flights get suggested.
