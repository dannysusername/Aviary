# flightlog-service

## The idea

The plane already broadcasts where it flies (ADS-B). That signal is public.

So: a Spring Boot service that grabs that signal for a tail number, works out
each flight (took off here, landed there, was up 1.4 hrs), and hands it to
AviaryService.

Instead of typing every flight into the logbook by hand, AviaryService says:

> "Found 4 flights since your last entry. Add them?"

Click yes. Hours update. Maintenance due dates update. Done.

**Auto-fill the logbook from the plane's own tracking data.**

## Why this project and not something else

It connects the two projects I already have:

- **charter-market-intel** already knows how to download ADS-B and cut a plane's
  day into separate flights. That's the hard part, already figured out in Python.
- **aviaryservice** needs exactly that and currently makes the user type it in.

So this is the missing piece between them, not a throwaway.

## The flow

```
owner registers N122AS in AviaryService
        │
        ▼
tail number -> ICAO hex, via the FAA registry
        │
        ▼
flightlog-service finds that hex's tracks (archive, or live poll)
        │
        ▼
cuts the day into flights (origin, destination, airborne time)
        │
        ▼
AviaryService: "4 flights detected — accept?"
        │
        ▼
accepted -> Hobbs/Tach roll forward -> maintenance due dates update
```

## Where the data actually comes from

Short answer: **not** a live adsb.lol call per user. That was the assumption and
it doesn't hold.

### How charter-market-intel gets it today

It downloads the **entire planet for one day** as a tarball from GitHub releases:

```
repo : adsblol/globe_history_2026
tag  : v2026.06.15-planes-readsb-prod-0
size : 3-4 GB per day, sometimes split into .tar.aa / .tar.ab
```

Inside are ~70,000 files, one per aircraft that flew anywhere on earth that day:

```
traces/<xx>/trace_full_<icaohex>.json.gz
```

Each is `{"icao": ..., "r": tail, "t": type, "trace": [[secs, lat, lon, alt, gs, ...], ...]}`.
extract_filter.py streams them, keeps the ones touching the South Florida box,
throws away the other ~68,000, deletes the tarball.

That works great for a research question. It is useless for "user signs up, show
me my flights" — you can't pull 3.5 GB per user on demand.

### Two things this forces

1. **Tail number -> hex.** The archive is keyed by ICAO hex (`a0f3c2`), not
   N-number. Mapping `N122AS` -> hex needs the FAA registry, which I already have
   as `registry.parquet` (314,913 aircraft). So the registry has to ship with
   this service, not stay in the Python project.
2. **PIA planes can't be served at all.** Aircraft on the FAA privacy program get
   a randomized hex that will never match the registry (see the DBFLAGS comment
   in config.py). For those users the honest answer is "your aircraft is on the
   FAA privacy program, we can't see it" — not a bug, a permanent state.

### So where does a new user's data come from?

Two designs, and the real answer is both:

**A. I run the pipeline, users query my database.**
One machine pulls the daily archive once, segments flights for every tail it
sees, writes them to Postgres. A new user's backfill is then just:

```sql
select * from detected_flight where reg = 'N122AS' order by dep_time_utc desc
```

Instant, no matter how many users. This is the only way "found 47 flights going
back 6 months" is possible at signup. Cost: I'm ingesting a few GB/day and have
to decide how much of the world to keep. Realistically keep a region, or keep
only tails that are registered or have ever been queried.

**B. Live polling for subscribed tails.**
adsb.lol also exposes a live REST API for current aircraft position (lookup by
registration or hex). A scheduled job polls subscribed tails, and I build flights
from the position stream as they happen. Cheap and fast, but it only knows about
flights from the moment someone subscribed - no history.

*(Confirm the exact live endpoints, rate limits, and terms of use before building
on B. Design A's archive path is the one already proven to work in Python.)*

**Decided: both, but in different places.**

- **A (daily archive) is its own service** — this repo, flightlog-service. It has
  to be separate: a 3-4 GB download that un-gzips 70,000 files cannot run in the
  same process as AviaryService's web dyno without taking the site down.
- **B (live poll) lives inside AviaryService** — a scheduled job checking a
  handful of subscribed tails is small. Not worth a second service.

A gives the "we already know your plane" moment at signup. B keeps people current
day to day. See STEPS.md for who owns which tables.

## The one rule that matters

ADS-B airborne time is **not** Hobbs time (that runs on oil pressure) and
**not** tach time (RPM-weighted). So the service only ever *proposes* an
estimate the user confirms. It never writes the meters directly.

This matches AviaryService's existing floor rule: logbook activity can raise
displayed hours, never lower them. Build this in from day one.

## Any user, not just one plane

Two modes:

**Backfill** — a new user types their tail number at signup, service pulls their
history: "found 47 flights going back 6 months, add them?" That's the moment the
app sells itself.

**Subscribe** — keep watching that tail. A new flight shows up a day later as
pending: "flew KTMB -> KEYW Tuesday, 1.4 hrs. Add?"

What that forces:

1. **Every flight needs a fingerprint** so re-checking doesn't offer the same one
   twice. `reg + departure time` works. Each detected flight is `pending`,
   `accepted`, or `dismissed` — a dismissed one stays gone.
2. **One poller, not one per user.** A scheduled job walks the subscribed tail
   numbers and pulls each once. Co-owners on the same plane share the pull.
3. **Verify the tail belongs to them.** The data is public — anyone could type
   N123AB and watch someone else's plane. At minimum check the FAA registry owner
   name against the account before allowing a subscription.
4. **"Found nothing" is a normal outcome**, not an error. No receiver coverage in
   the area, or the owner is on the FAA privacy program (LADD/PIA). Needs a real
   state in the UI.

Extra tables this adds: `subscription` (user, tail, active, last_checked) and a
status column on `detected_flight`.

## What makes it a real backend project

- thousands of track points per flight -> batch inserts, not save() in a loop
- backfill returns a job ID, worker processes it, client polls status
- Flyway migrations: aircraft, track_point, detected_flight, ingest_job
- indexes on (reg, day) and (reg, dep_time_utc)
- adsb.lol goes down sometimes (June 11 outage) -> retry, backoff, cached last-good
- Testcontainers against real Postgres; replay a saved trace file, assert the
  segmenter finds N flights
- AviaryService gets a typed HTTP client with a timeout and a fallback to
  manual entry

Stack: Java 21, Spring Boot, Maven, Postgres, Flyway, JUnit 5 + Testcontainers.

## First milestone

Take one trace file I already have:

```
charter-market-intel/data/interim/sofla_traces/2026-06-26/<hex>.json.gz
```

Parse it in Java, find the flights in it, print them. Check the answer against
`./peek_parquet.py legs <tail>` — the Python already worked these out, so I have
a known-good result to test against.

No database, no API, no AviaryService. Just prove the segmenting works.

Then in order: FAA registry loader (N-number -> hex) -> Postgres + Flyway ->
REST endpoint -> AviaryService client -> subscriptions.
