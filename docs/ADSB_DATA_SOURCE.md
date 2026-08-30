# ADS-B Data Source

Status: **Verified 2026-08-29** · Scope: where the flight data comes from, nothing else

This answers one question only: *when a user wants their flight history, what do we
call and what do we get back?* Feature design lives in
[ADSB_SYNC_SPEC.md](ADSB_SYNC_SPEC.md).

## Decision

**Use adsb.lol's per-aircraft trace files. Do not download the daily archive.**

Everything within the last ~23 days is available as a single small file per
aircraft per day. That covers the backfill-at-signup moment and the daily poll.
The 4 GB archive is out of scope (see [Not in scope](#not-in-scope)).

## The two endpoints

### 1. Today — live position

```
GET https://api.adsb.lol/v2/reg/{registration}
```

No API key. Returns the aircraft's current position, or an empty `ac` array if
it isn't flying right now.

```json
{"hex":"a4e8ef", "r":"N415YX", "t":"E75L", "flight":"RPA4351",
 "lat":25.777231, "lon":-80.843569, "alt_baro":3900, "gs":225.0}
```

An empty result is a **normal state**, not an error — the plane is parked.

### 2. Past days — full trace

```
GET https://adsb.lol/globe_history/{YYYY}/{MM}/{DD}/traces/{xx}/trace_full_{hex}.json
```

- `{hex}` — the aircraft's ICAO hex, lowercase (`a4e8ef`)
- `{xx}` — the **last two characters** of that hex (`ef`)

Example:

```
https://adsb.lol/globe_history/2026/08/20/traces/ef/trace_full_a4e8ef.json
```

Returns roughly **130 KB gzipped** — one aircraft, one full day of positions.

## Retention: ~23 days

Measured 2026-08-29, day by day:

| Days ago | Result |
|---|---|
| 1–23 | `200` |
| 24+ | `404` |

Checked against 5 different aircraft on the old dates — 0 of 5 returned data, so
the days are genuinely deleted rather than those planes not having flown.

**Consequence:** a new user can be offered their **last ~3 weeks**, not their last
year. Everything after signup comes from our own polling.

Treat 23 as approximate and probe the boundary rather than hardcoding it.

## What a trace contains

```json
{"icao":"a4e8ef", "r":"N415YX", "t":"E75L", "timestamp": 1787944218.557,
 "trace": [[138.07, 39.144985, -84.687073, 4825, 261.5, 74.5, 0, 1728,
            null, "adsb_icao", 5125, 2176, null, null], ...]}
```

- `timestamp` — epoch seconds for the start of the day
- each row's first element is **seconds offset from `timestamp`**, not an absolute time
- ground speed is present (`261.5` kt above) — this is what the 35 kt airborne
  threshold reads

### Position sources

Each point is tagged with where it came from. A sample day on one aircraft:

```
adsb_icao : 1221 points     the aircraft broadcasting its own GPS position
tisb_icao :    1 point      ground radar, rebroadcast
```

Other possible tags: `mlat` (position computed from signal timing, for aircraft
without ADS-B Out) and `adsr_*` (rebroadcast between frequencies).

US aircraft have been required to carry ADS-B Out in most controlled airspace
since 2020, so `adsb_icao` dominates. Filter to it if strictness is ever needed.

## Open problem: registration → hex

The trace URL is keyed by **ICAO hex**, but users type an **N-number**.

`/v2/reg/{registration}` returns the hex — but only while the aircraft is
airborne. A parked plane returns nothing, so it cannot be relied on for backfill.

A registry lookup is therefore required. The FAA registry is already on disk from
the charter-market-intel project:

```
charter-market-intel/data/raw/MASTER.txt      184 MB
charter-market-intel/data/raw/ACFTREF.txt      14 MB
```

Unresolved: whether that ships with AviaryService or lives behind a lookup call.

## What the API cannot do

Pulled the full endpoint list from `https://api.adsb.lol/api/openapi.json` —
19 endpoints, **all current-position only**:

```
/v2/reg/{registration}      /v2/hex/{icao_hex}       /v2/callsign/{callsign}
/v2/registration/{reg}      /v2/icao/{icao_hex}      /v2/squawk/{squawk}
/v2/lat/{lat}/lon/{lon}/dist/{radius}                /v2/type/{aircraft_type}
/v2/closest/{lat}/{lon}/{radius}                     /v2/mil  /v2/pia  /v2/ladd
```

**There is no history endpoint.** The trace URLs above are static files served
outside the API, which is why they don't appear here.

`re-api.adsb.lol` (feeders only) is also live data, and is locked to the feeding
station's IP address. It does not help.

## Licensing

Data is **ODbL 1.0**. Attribution required if the data is redisplayed.
Terms: https://www.adsb.lol/docs/open-data/

## If the ~23-day limit becomes the blocker

Free ADS-B can only offer what a volunteer receiver happened to hear. Measured on
N122AS, that was **8 flying days out of 43 checked** — most days have no data at
all, and even good days have coverage gaps mid-flight. No amount of engineering
fixes that; the data was never recorded.

A commercial API is the way out, because it isn't limited to receivers.

Endpoint shapes, parameters, and field names below were read from each vendor's
published OpenAPI spec on 2026-08-29. **Pricing and tier names are still
unverified** — confirm those before planning around them.

### FlightAware AeroAPI

Spec: `https://static.flightaware.com/rsrc/aeroapi/aeroapi-openapi.yml`
(v4.30.0, 64 endpoints). The web docs page is a JS shell; the spec is the
readable source.

**Auth is a static API key in a header** — no OAuth, no token refresh:

```
x-apikey: <key>
```

> "Unlike previous versions of AeroAPI, authentication is now controlled by an
> API key that must be set in the header `x-apikey`. Your FlightAware username is
> not used when authenticating to the API."

**The endpoint we want:**

```
GET https://aeroapi.flightaware.com/aeroapi/history/flights/{ident}
      ?start=2026-07-01&end=2026-07-08
```

- `{ident}` accepts a **registration** — the spec says a passed ident "is
  interpreted as a registration if possible", so `N122AS` works directly. No hex
  lookup, no FAA registry.
- `start`/`end` are **required** for a registration, and the span can be **at most
  7 days**. A year of backfill is ~52 calls.
- Data available **back to 2011-01-01 00:00:00 UTC**.
- Max 40 pages per request; paginate with `cursor`, response carries `num_pages`
  and `links.next`.

**Fields that matter for a logbook**, from the response schema:

| Field | Meaning |
|---|---|
| `actual_off` | actual runway departure — wheels up |
| `actual_on` | actual runway arrival — wheels down |
| `origin.code_icao` / `destination.code_icao` | real airport codes |
| `filed_ete` | runway-to-runway duration (seconds) |
| `route_distance` | planned distance (statute miles) |
| `aircraft_type` | ICAO type code |
| `type` | enum: `General_Aviation` \| `Airline` |
| `diverted`, `cancelled`, `position_only`, `blocked` | status flags |

`actual_off` → `actual_on` **is** the airborne time. Everything the ADS-B path
computes by hand — the 35 kt threshold, null handling, UTC stitching,
nearest-airport guessing — arrives here as plain fields.

`blocked` is the privacy-program case delivered as a boolean rather than an
unexplained 404.

**Also useful:** `GET /history/aircraft/{registration}/last_flight` returns only
the most recent flight for a tail. That is a cheap "has anything happened since we
last checked?" poll — far lighter than re-running a date range.

The real advantage over free ADS-B is **it fills the gaps where no receiver was
listening**, which is exactly the failure mode above.

### Tiers — what we can actually call

| Tier | Cost | `/history/` endpoints |
|---|---|---|
| **Personal** | $5 free/month ($10 if you feed ADS-B) | ❌ |
| Standard | **$100/month minimum** | ✅ |
| Premium | $1,000/month minimum | ✅ |

Historical is $0.020/result set, but the **$100/month floor** is the real wall.

**So plan on `GET /flights/{ident}` (no `/history/` prefix), which Personal can
call.** It returns ~14 days, which is enough if we poll and never fall behind.

### Reference: `GET /flights/{ident}`

```
GET https://aeroapi.flightaware.com/aeroapi/flights/N122AS
Header: x-apikey: <key>
```

Returns ~14 days of recent **and scheduled** flights for a tail number.

#### Request parameters

| Param | Notes |
|---|---|
| `ident` | path, required. The tail number — `N122AS`. |
| `ident_type` | `designator` \| `registration` \| `fa_flight_id`. Defaults to registration. **We can omit it.** |
| `start` | **Inclusive.** Max 10 days back / 2 days ahead. Omit → ~11 days back. |
| `end` | **Exclusive.** Same limits. Omit → ~2 days ahead. |
| `max_pages` | Default 1. Each page is billed as its own query — leave at 1. |
| `cursor` | Bookmark for the next batch. Comes from `links.next`; pass it back unchanged. |

Bounds differ: to get all of Aug 23 use `start=2026-08-23&end=2026-08-24`.
A bare date means **00:00:00Z**, which is the previous evening in Miami — pass
full datetimes when the day boundary matters.

**Simplest usage: omit `start`/`end` entirely.** The default window is what the
poller wants.

#### Response envelope

```json
{ "flights": [ ... ], "links": null, "num_pages": 1 }
```

- `flights` — array, **newest first**
- `links.next` — cursor for more results; `null` means you have everything
- `num_pages` — pages returned

#### Time fields — OOOI

Aviation records four moments. GA has no gates, so only the middle two exist:

| | Meaning | GA |
|---|---|---|
| `*_out` | pushed back from gate | always `null` |
| `*_off` | **wheels up** | ✅ |
| `*_on` | **wheels down** | ✅ |
| `*_in` | parked at gate | always `null` |

Each has three variants — `scheduled_`, `estimated_`, `actual_`. **Use `actual_`
only**; the others are predictions.

> **`actual_off` → `actual_on` is the flight.** Duration = the difference between
> them. This is *airborne* time, not Hobbs.

#### Fields we use

| Field | Meaning |
|---|---|
| `actual_off` / `actual_on` | wheels up / wheels down (UTC) |
| `origin.code_icao` / `destination.code_icao` | airport codes — `KTMB`, `KEYW` |
| `origin.name` / `.city` | display names — "Miami Exec" |
| `origin.timezone` | e.g. `America/New_York`; needed to show the pilot's local day |
| `fa_flight_id` | unique per leg — **our dedupe key** |
| `inbound_fa_flight_id` | the aircraft's previous leg; chains flights together |
| `registration` | confirms the right aircraft |
| `aircraft_type` | ICAO type — `SR22` |
| `status` / `progress_percent` | `"Arrived"` / `100` — only import when complete |
| `blocked` | owner blocked public tracking |
| `diverted` / `cancelled` | edge cases to skip or flag |
| `position_only` | true = no flight plan, tracking only |
| `type` | `General_Aviation` \| `Airline` |
| `actual_runway_off` / `actual_runway_on` | runways used, when detected |
| `route` | filed IFR route string, when filed |

#### Fields always null for GA

`operator*`, `flight_number`, `ident_icao`, `ident_iata`, `atc_ident`,
`codeshares*`, `baggage_claim`, `gate_*`, `terminal_*`, `seats_cabin_*`.

Airline fields sharing the same schema. Ignore them.

#### Gotchas

- **`filed_ete` is the filed *estimate*, not reality.** One observed leg filed
  2,440 s and flew 3,017 s. Always compute `actual_on − actual_off`.
- **`route_distance: 0` does not mean no flight** — a round trip to the same
  airport has zero straight-line distance. Never filter on it.
- **`filed_altitude: 80` means 8,000 ft** — units are hundreds of feet.
- **`departure_delay: -577` means 577 seconds *early*** — negative is early.
- Fields drop to `null` when not detected, including runways. Guard every read.

#### Worked example — N122AS, Aug 23–24 2026

```
Sat Aug 23   KTMB → KEYW   17:22:41Z → 18:13:36Z   50m 55s
Sat Aug 23   KEYW → KTMB   18:32:07Z → 19:22:24Z   50m 17s
Sun Aug 24   KTMB → KTMB   18:55:46Z → 19:55:41Z   59m 55s
```

Local (UTC−4): flew to Key West at 1:22 PM, 19 minutes on the ground, home by
3:22 PM. Next day an hour out of KTMB and back.

**For the same Saturday, free ADS-B gave us 23 minutes of the 101 minutes flown
(~22%) and could not name a single airport** — receivers lost the aircraft over
the Keys. AeroAPI has both legs complete, both airports, both runways.

### Where FlightAware's data comes from

Not just receivers — a formal FAA data-sharing agreement dating to around 2006,
when they were granted access to the ASDI (Aircraft Situation Display to Industry)
feed.

The FAA publishes through **SWIM** (System Wide Information Management), mainly
SFDPS (flight data) and STDDS (terminal data). Two access tiers:

| Tier | Delay | Who | Redistribute? |
|---|---|---|---|
| 1 | real-time | requires "operational need" (airlines, dispatchers, ANSPs) | **no** |
| 2 | 5 minutes | what FlightAware operates under | **yes** |

The feed carries flight plans, departure/arrival events, radar positions, route
amendments, and gate info.

**Could we get it directly?** Technically yes — apply through the FAA SWIM portal.
In practice it means a slow approval process requiring a demonstrated use case,
and what arrives is a raw feed, not a clean REST API. We would then have to build
the ingestion pipeline, correlate it against ADS-B positions, and repeat the whole
exercise for 45+ other countries' ANSPs.

FlightAware has already done all of that and sells it as one REST call. That is
what the money buys.

### Flightradar24 — Flight Summary

**Correction.** An earlier draft of this file recorded FR24 as "positions only,
no flight records by tail." That is **wrong**. FR24 has a Flight Summary API that
returns exactly the same kind of structured flight records:

```
GET https://fr24api.flightradar24.com/api/flight-summary/full
      ?registrations=N122AS
      &flight_datetime_from=2026-07-01T00:00:00
      &flight_datetime_to=2026-07-15T00:00:00
```

- `registrations` filter accepts **up to 15 tail numbers in one call**
- Window is **up to 14 days** per query — twice AeroAPI's
- History available from **2022-06-01** onward (fixed start date, so the depth
  grows; they state they are backfilling older flights)
- Up to 20,000 results per query
- Two variants: `/flight-summary/light` and `/flight-summary/full`

Light already returns `datetime_takeoff`, `datetime_landed`, `origin_icao`,
`destination_icao`, `destination_icao_actual`, `reg`, `hex`, and `flight_ended`.
Full adds runways, `flight_time`, `circle_distance`, and service categories.

**Light is enough for a logbook.**

Credits are charged **per returned record**, not per call:

| | Light | Full |
|---|---|---|
| Live | 1 | 2 |
| Historic ≤30 days | 2 | 3 |
| Historic >30 days | 3 | 6 |

Per-record billing suits us: N122AS flew ~10 times in 20 days, so a year of
backfill is roughly 150 records — a few hundred credits, not the 20,000-row
ceiling.

### AeroAPI vs. FR24 — verified

| | AeroAPI | FR24 Flight Summary |
|---|---|---|
| Query by tail | yes | yes |
| Tails per call | 1 | **15** |
| Window per call | 7 days | **14 days** |
| History back to | **2011-01-01** | 2022-06-01 |
| Calls for 1-year backfill | ~52 | ~26 |
| Auth | `x-apikey` header | API token |
| Billing | per query | per returned record |

Both do the job. AeroAPI wins on history depth; FR24 wins on call efficiency —
wider window *and* multiple tails per request, which matters once several
subscribed aircraft are being polled.

For a logbook, 2011 depth is largely irrelevant — users want their own recent
flying. **Price per unit is the deciding factor and has not been compared yet.**

### Proposed: users connect their own AeroAPI key

Rather than AviaryService paying for API access, a user supplies their own key.

**Flow.** There is no OAuth — AeroAPI authenticates with a static header — so
"connect" means:

1. User creates a FlightAware account and adds billing
2. User generates an API key in the AeroAPI portal
3. User pastes it into AviaryService settings
4. We store it encrypted and send it as `x-apikey` on their behalf

**Why it is worth doing:** it ships the feature without solving billing first.
Each user pays FlightAware directly, so there is no cost exposure and no payment
integration on the critical path.

**Why it should not be the only path:** steps 1–2 ask an aircraft owner who wants
a logbook to sign up for a *developer API* and enter card details. Expect low
uptake. Treat this as a power-user option, not the primary onboarding.

**Two obligations it creates:**

- **The key is a billable secret.** If it leaks, someone else spends that user's
  money. Encrypt at rest, never log it, never expose it to the frontend.
- **Support lands on us**, not FlightAware — "why was I charged?" arrives here.

**Design note.** Put credential lookup behind a `CredentialProvider` interface
rather than reading a key off the `User` entity. Then "use the user's key" and
"use our key, bill them via subscription" are the same code path with a different
provider, and switching models later is not a rewrite. This is what the existing
`Subscription` entity eventually grows into.

### Also relevant to the deadhead project

AeroAPI returns **flight plans**, not just where an aircraft has been. Knowing
where a plane is *going* is what makes deadhead detection possible — an empty
repositioning leg can be spotted before it flies, rather than discovered
afterward. Worth keeping in mind when that project needs a data source.

## Not in scope

**The daily archive.** `github.com/adsblol/globe_history_2026` publishes every
aircraft on earth, ~4.15 GB/day as an uncompressed tar split into 2 GB parts.

It is the only source for data older than ~23 days. Extracting one aircraft is
possible without downloading the whole file — the tar is uncompressed and GitHub
honours Range requests — but it is expensive, because the 256 `traces/xx/`
folders are stored in an order that **changes every day**, so the location cannot
be cached or binary-searched. You have to hunt for it each time.

**Measured**, pulling N122AS out of 20 daily archives (2026-07-01 → 07-20):

| | Direct URL (≤23 days) | Tarball hunt |
|---|---|---|
| Requests per plane-day | 1 | **267** |
| Downloaded per plane-day | 0.6 KB | **80 MB** |
| 20 days total | — | 5,347 requests, 1.61 GB, 5.9 min |

Roughly 75% of that cost is the *search*, not the download — one run spent 480
requests and 94 MB locating the folder, then 25 MB pulling it. The file itself is
about 5 KB.

Downloading all 20 archives whole would have been 81 GB, so the technique saves
50×. But it is still ~130,000× more expensive per day than the direct URL, which
is why it cannot run while a user waits.

Working tool: `scratchpad/tarhunt.py`. Deferred to `flightlog-service`.
See [ADSB_BACKGROUND.md](ADSB_BACKGROUND.md).

## Verification log

All checked 2026-08-29 against live endpoints:

- `/v2/reg/N122AS` → `200`, empty `ac` (aircraft not airborne)
- `/v2/lat/25.79/lon/-80.29/dist/50` → `200`, 70 aircraft
- `globe_history/2026/08/{06,07,08,09,10,13,15,17,19,20,25,28}` → `200`
- `globe_history/2026/{08/05, 08/01, 07/15, 07/29, 02/28}` → `404`
- `api.adsb.lol/api/openapi.json` → 19 endpoints, no history endpoint
