# ADS-B Flight Sync Spec

Status: **Designed, not yet built** · Last updated: 2026-08-26

Aircraft broadcast their position publicly (ADS-B). This feature watches a user's
tail number, works out when the plane flew, and offers those flights as log book
entries instead of making the user type them in.

Background and the wider two-service plan: **[ADSB_BACKGROUND.md](ADSB_BACKGROUND.md)**.

## Scope of this document

This is **only** the part that lives inside AviaryService: the live poll.

A second project (`flightlog-service`) will later ingest the daily global ADS-B
archive and serve *history*, so a brand-new user can be offered months of past
flights at signup. That does not exist yet, and **nothing here depends on it**.
Build this standalone; the backfill plugs in later (see "Later" at the bottom).

## UX flow

```
 Log Book tab
 ┌────────────────────────────────────────────────┐
 │  ✈ 2 flights detected since your last entry    │
 │                                                │
 │   Tue Aug 25   KTMB → KEYW    1.4 hrs          │
 │                              [Add]  [Dismiss]  │
 │   Sun Aug 23   KTMB → KTMB    0.8 hrs          │
 │                              [Add]  [Dismiss]  │
 └────────────────────────────────────────────────┘
              │ Add
              ▼
 ┌──────────── Add flight ─────────────┐
 │  From [KTMB]      To [KEYW]         │  ← pre-filled
 │  Hobbs out [____]  Hobbs in [____]  │  ← user fills, we suggest
 │  Tach  out [____]  Tach  in [____]  │  ← user fills
 │                                      │
 │  Detected airborne time: 1.4 hrs     │  ← what we actually know
 │                                      │
 │             [Cancel]  [Save]         │
 └──────────────────────────────────────┘
```

The banner only appears when there is something pending. Dismiss is permanent for
that user — a dismissed flight is never offered again.

## Turning it on

In the Aircraft Info card, next to tail number:

- **[Sync flights automatically]** toggle, off by default.
- Turning it on creates a `subscription` row.
- Before allowing it, check the tail belongs to them (see Ownership below).

## What we can and cannot know

This matters more than anything else in this spec.

The Garmin CSV parser (`UserController.java:478-484`) derives two different times:

| Number | How it's derived today | Can ADS-B do it? |
|---|---|---|
| **Block time** (Hobbs) | oil pressure > 15 psi | **No.** ADS-B has no engine data. |
| **Airborne time** | GndSpd > 35 kt, 3+ sec | **Yes.** ADS-B has ground speed. |

So ADS-B can reproduce the **airborne** number and nothing else. Hobbs runs on
oil pressure and therefore includes taxi, run-up, and shutdown — it is always
*longer* than airborne time. Tach is RPM-weighted and unrelated.

**Therefore:** show the detected airborne time as a fact, pre-fill from/to and
date, and let the user enter Hobbs and Tach themselves. Do not guess meters.

## Rules that cannot be broken

1. **Propose, never write.** A detected flight is a suggestion until a human
   presses Add. Nothing touches the log book or the meters on its own.
2. **Hours go up, never down.** The existing floor rule stands. A sync must never
   be able to lower a displayed Hobbs or Tach value.
3. **Never break manual entry.** If the ADS-B source is slow or down, the banner
   just doesn't appear. Time the call out. Never hang the dashboard on it.
4. **Same threshold as the Garmin parser.** 35 kt. If `flightlog-service` later
   uses a different number, the two will disagree about whether a flight
   happened. Keep them identical; extract to a shared constant if it drifts.

## Data model

Three new tables. All user-scoped, like everything else in this app.

```
subscription
    id, user_id, n_number, active, last_checked_at

live_position                      (raw poll results, prunable after ~30 days)
    id, n_number, seen_at, lat, lon, alt_ft, ground_speed_kt, on_ground

flight_suggestion
    id, user_id, source, n_number,
    dep_time_utc, arr_time_utc, origin, dest, minutes_airborne,
    status, created_at
```

- `status` — `pending` / `accepted` / `dismissed`
- `source` — `live` now, `archive` later when flightlog-service exists
- unique on `(user_id, n_number, dep_time_utc)` so the same flight can never be
  offered twice, no matter how many times the poller runs
- accepting a suggestion creates a normal `FlightLog` row; the suggestion keeps
  its own row with `accepted` so it stays out of the way

## The poller

A `@Scheduled` job. Suggested every 15 minutes to start.

```
for each active subscription:
    hit the live position API for that tail
    store the position in live_position
    if the plane was airborne and is now on the ground for N consecutive polls:
        a flight just ended
        find its start from earlier live_position rows
        resolve origin/dest to airport codes
        write a flight_suggestion (pending)
    update last_checked_at
```

Notes:

- Poll all tails in one pass; do not schedule per user.
- Back off on errors. If the API fails, log it and move on — never let one bad
  tail stall the loop.
- The plane can be out of receiver range mid-flight (this is exactly what
  happened to Bahamas flights in the charter-market-intel data — coverage dies
  over the Gulf Stream). A gap is not a landing. Require the plane to actually be
  seen on the ground before calling a flight finished, and if it simply
  disappears, mark the suggestion `incomplete` rather than inventing an end time.

## Before writing code: verify the data source

**This is unconfirmed and blocks the poller.** adsb.lol's live API — the exact
endpoints, whether lookup by registration works or whether the ICAO hex is
required, rate limits, and terms of use — has not been checked.

Confirm all of that first.

- If lookup **by registration** works, AviaryService needs nothing else.
- If only **hex** works, AviaryService also needs a tail-number-to-hex mapping,
  which means the FAA registry, which is 193 MB. In that case the better move is
  to build `flightlog-service` first and let it answer that lookup.

That single answer decides whether this feature is small or not. Check it before
anything else.

## Ownership

The data is public — anyone could type a stranger's tail number and watch their
plane. Before allowing a subscription, cross-check the FAA registry owner name
against the account holder.

Also handle the honest dead ends, as normal states and not errors:

- **no coverage** — no ADS-B receiver picked the plane up. Nothing to show.
- **FAA privacy program (LADD / PIA)** — the aircraft broadcasts a scrambled
  identifier that will never match the registry. This user can never be served by
  this feature. Say so plainly.

## Testing

- Save a real sequence of poll responses as a fixture; assert the detector finds
  the right number of flights.
- Test the coverage-gap case explicitly: plane vanishes mid-flight, must **not**
  produce a completed flight.
- Test that accepting a suggestion twice doesn't create two log entries.
- Test that a sync can never lower Hobbs or Tach.

## Later — the backfill

Once `flightlog-service` exists, it serves history from the daily archive. The
only change here: call it when a user first turns on sync, and write what comes
back as `flight_suggestion` rows with `source = 'archive'`.

The UI, the tables, and the accept/dismiss flow are already correct for that.
That is why `source` exists from day one.
