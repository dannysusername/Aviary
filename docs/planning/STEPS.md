# What I need to do

The plan in order. Don't skip ahead. Each step says how I know it's done.

Read IDEA.md first if I forgot what this is.

---

## Step 0 — clean up the tutorial scaffold

Right now this project is still the Spring Boot hello-world tutorial.

- rename `artifactId` in pom.xml from `myproject` to `flightlog-service`
- rename the package from `com.example.myproject` to `com.flightlog`
- delete the `home()` "Hello World" method
- `git init` (this folder isn't even a repo yet)

**Done when:** `mvn spring-boot:run` starts and doesn't crash.

---

## Step 1 — read one trace file

I already have real data sitting on disk from the Python project:

```
../charter-market-intel/data/interim/sofla_traces/2026-06-26/<hex>.json.gz
```

Write Java that opens one of those, un-gzips it, and prints the points.

The file looks like:

```json
{"icao":"a0f3c2", "r":"N122AS", "t":"SR22",
 "trace":[[secs, lat, lon, alt, groundspeed, ...], ...]}
```

`alt` is either a number or the string `"ground"`. That matters later.

**Done when:** I can print "N122AS, 1,847 points, first at 14:02Z, last at 21:30Z".

---

## Step 2 — cut the day into flights

One file = one plane's whole day. That might be 4 separate flights.

A flight is: plane leaves the ground, flies, comes back down.

Rough rule to start:
- on the ground = `alt` is `"ground"` or groundspeed under 35 kt
- when it goes from ground -> air, a flight started
- when it goes air -> ground and stays there, that flight ended

35 kt isn't random — that's the same threshold AviaryService's Garmin CSV parser
already uses for airborne time. Reuse the number so both agree.

**Done when:** one file in, a list of flights out, each with start time, end time,
and minutes airborne.

---

## Step 3 — check my answer against the Python

I don't have to guess if Step 2 is right. The Python already did this.

```bash
cd ../charter-market-intel
./peek_parquet.py legs N122AS
```

Compare. Same number of flights? Same times, roughly?

**Done when:** my Java finds the same flights the Python found. Now I trust my
segmenter and I can build on it.

---

## Step 4 — where did it take off and land

I have lat/lon. I need "KTMB" and "KEYW".

`data/raw/airports.csv` in charter-market-intel is the 85k OurAirports list.
Load it, and for the first and last point of each flight, find the nearest
airport within ~3 nautical miles. (Deadhead's `AirportDb` already does this —
look at how it did it, it's the same job.)

**Done when:** a flight now reads `KTMB -> KEYW, 1.4 hrs`.

---

## Step 5 — tail number -> hex code

The trace files are named by hex code (`a0f3c2`), not tail number (`N122AS`).
A user types their tail number, so I need to translate.

The FAA registry has this. `MASTER.txt`, 193 MB, fixed-width columns.
charter-market-intel's `faa_registry.py` already parses it — read that to see
which columns matter.

Load it into a table: `n_number, icao_hex, make, model, year, owner_name`.

Bonus: that same table gives me make/model/owner, which AviaryService currently
makes people type in by hand.

**Done when:** `lookup("N122AS")` returns the hex code and the aircraft details.

---

## Step 6 — put it in Postgres

Now it needs a real database instead of printing to the console.

Tables:

```
aircraft          n_number, icao_hex, make, model, year, owner_name
detected_flight   reg, dep_time_utc, origin, dest, minutes_airborne
tracked_tail      n_number, added_at        (which tails to keep from each archive)
ingest_job        day, status, started_at, finished_at
```

Note what is NOT here: users, subscriptions, and accepted/dismissed status.
This service has no idea who its users are. It only knows planes and flights.
AviaryService owns all the people stuff. See "Who owns what" at the bottom.

Use **Flyway** for the schema — `.sql` files in version control, not
`ddl-auto=update`. (AviaryService uses ddl-auto. Don't copy that here; Flyway is
what real backends do and interviewers ask about it.)

Indexes on `detected_flight (reg, dep_time_utc)`. That's the only thing I ever
query by.

Give each flight a stable id built from `reg + dep_time_utc`, so if I re-ingest a
day I update the existing row instead of creating a duplicate — and AviaryService
can refer to a flight by that id forever.

**Done when:** I can run Step 1-5 on a file and the flights land in Postgres.

---

## Step 7 — the daily download

This is the part that makes it work for strangers.

Once a day, download that day's global archive, run everything above on it,
store the flights. Then any user's history is already sitting in my database.

```
repo : adsblol/globe_history_2026
tag  : v2026.06.15-planes-readsb-prod-0
size : 3-4 GB, sometimes split into .tar.aa / .tar.ab
```

`download.py` in charter-market-intel already does this correctly, including the
curl retry logic for when wifi dies. Port it.

Decide what to keep. Keeping the whole planet forever is not realistic.
Rule: keep flights for any tail in `tracked_tail`, plus a region I care about
(South Florida, same box the Python uses) so there's history waiting when a new
user from that area signs up.

AviaryService adds a tail to `tracked_tail` when a user registers their plane.

**Done when:** it runs on a schedule, and yesterday's flights show up without me
touching anything.

---

## Step 8 — the API

Now expose it so AviaryService can ask.

```
GET  /api/v1/aircraft/{tail}/flights?since=2026-06-01   the history
GET  /api/v1/aircraft/{tail}                            make, model, year, owner
POST /api/v1/tracked-tails            { tail }          start keeping this one
```

No user accounts, no auth per person. AviaryService is the only caller — a shared
API key between the two services is enough.

**Done when:** curl gives me back N122AS's flights as JSON.

---

## Step 9 — hook it into AviaryService (the backfill)

The payoff. This is the signup moment.

User registers N122AS. AviaryService calls flightlog-service, gets the history
back, and shows:

> "Found 47 flights going back 6 months. Add them?"

AviaryService needs one new table of its own:

```
flight_suggestion   user_id, external_flight_id, status, seen_at
```

`status` is `pending` / `accepted` / `dismissed`. This lives here, not in
flightlog-service, because it's a decision a *person* made. Two co-owners of the
same plane can each accept or dismiss the same flight independently.

Rules that cannot be broken:
- it **proposes**, the user accepts. Never write Hobbs/Tach automatically.
- ADS-B airborne time is not Hobbs time and not tach time. It's an estimate.
- hours can go up, never down. AviaryService already has this floor rule.
- if flightlog-service is down or slow, AviaryService still works — time it out,
  fall back to manual entry. Never hang the page on it.

**Done when:** I register a tail in AviaryService and flights appear without
typing anything.

---

## Step 9b — the live poll (inside AviaryService)

Backfill only covers up to yesterday's archive. For "you flew this morning,"
AviaryService polls for itself.

A `@Scheduled` job walks the subscribed tails, hits the live position API, and
notices when a plane goes airborne and then lands. That becomes a pending
suggestion, same as a backfilled one.

Tables:

```
subscription        user_id, n_number, active, last_checked
live_position       n_number, seen_at, lat, lon, alt, groundspeed
```

Two things to watch:

1. **Verify the live endpoints first.** I have not confirmed adsb.lol's live API,
   its rate limits, or its terms of use. Do that before building on it.
2. **The airborne rule now exists in two codebases.** flightlog-service segments
   full trace files; AviaryService watches live snapshots. Keep the *threshold*
   identical (35 kt, same as the Garmin parser) so the two never disagree about
   whether a flight happened. If it starts drifting, pull the rule into a small
   shared jar.

**Done when:** I fly today and it shows up in AviaryService tomorrow morning
without the archive having run.

---

## Step 10 — make it real

- **Verify the tail is theirs.** Anyone could type someone else's tail number and
  watch their plane. Check the FAA registry owner name against the account.
- **Handle "found nothing."** No coverage in their area, or their plane is on the
  FAA privacy program (PIA) and has a scrambled hex that won't match the registry.
  That's a normal message, not an error.
- **Tests.** JUnit + Testcontainers against a real Postgres. Save one trace file
  as a test fixture and assert the segmenter finds the right flights every time.
- **CI/CD.** GitHub Actions build + test on push. Deploy to Heroku like the other
  two projects.

---

## Order I'd actually do it in

Steps 1-4 are one weekend and they're the interesting part. If I do nothing else,
I still have a thing that turns raw flight data into readable flights.

Steps 5-8 turn it into a service.

Step 9 is the one that makes it worth showing someone.

Step 10 is what makes it a project instead of a demo.

---

## Who owns what

Decided: two pieces, not one.

**flightlog-service** (this repo) — the heavy lifting.
- downloads the 3-4 GB daily global archive
- cuts traces into flights
- FAA registry (tail number -> hex, make/model/owner)
- serves the history over a small API
- knows nothing about users

**AviaryService** — the people part.
- users, accounts, subscriptions
- polls live positions for subscribed tails
- shows "add these flights?" and remembers what each user answered
- writes the logbook, rolls Hobbs/Tach, updates maintenance dates

**Why split:** not because microservices are fashionable. Because a 3-4 GB
download that un-gzips 70,000 files cannot run in the same process as the web app
my dad is using. It would blow the dyno's memory and take the site down. Heavy
batch and small web app want different machines.

The live poll stays in AviaryService because it's small — a scheduled job hitting
an API for a handful of tails. Not worth a second service.
