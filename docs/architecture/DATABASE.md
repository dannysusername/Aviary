# Database Map

> Covers the JPA entities (`src/main/java/com/example/AviaryService/entity/**`) and datasource config. Update this file when you add/change an entity, column, relationship, or datasource setting.

## Engines
- **Local / default:** profile `h2` (`application.properties` → `spring.profiles.active: h2`). No datasource is configured, so Spring Boot auto-configures an **in-memory H2** database. **Data is lost on every restart.**
- **Production (Heroku):** profile `heroku` (`application-heroku.properties`). Uses Postgres via `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (Heroku sets these from `DATABASE_URL`). `ddl-auto=update` (Hibernate auto-creates/alters tables), Postgres dialect, `open-in-view=false`.
- There is a tracked file named `dbdata` at the repo root (purpose unconfirmed — verify whether it holds local DB state or config).

## Tables (entity → table)
All ids are `@GeneratedValue(IDENTITY)`. Every row belongs to a `User` via `user_id` (FK, not null). Per-plane rows also belong to one `Aircraft` via `aircraft_id` (see below).

### `users` (`User`)
`id`, `username` (unique, not null), `password` (BCrypt, not null), `aeroApiKey` (encrypted, see `AeroApiKeyConverter`; one key per account), `last_viewed_aircraft_id` (FK → `aircraft`, nullable — the aircraft the dashboard opens on).
One-to-many (cascade ALL) → `aircraft`, `serviceTimeline`, `flightLogs`.

**Legacy columns (kept, unmapped):** `block_time_hours`, `time_in_service_hours`, `make_model`, `tail_number`, `owner_name`, `make_modelsn`, both `*_updated_at`, both `*_updated_source`, both `*_manual_baseline`. These held the single aircraft before fleets. `AircraftBackfill` copies them into each account's first aircraft on boot; nothing else reads them. They stay one release so a rollback still works, then get dropped (see `docs/migrations/2026-11-09_multi_aircraft.sql`).

### `aircraft` (`Aircraft`)
`id`, `user_id` (not null), `tailNumber` (upper-cased, spaces removed), `tail_number_key` (comparison key, null when blank; unique with `user_id`), `makeModel`, `ownerName`, `makeModelSN`, `blockTimeHours` / `timeInServiceHours` (Double), `blockTimeManualBaseline` / `timeInServiceManualBaseline` (Double, manually-set floor so log activity can't silently lower the displayed total), `blockTimeUpdatedAt` / `timeInServiceUpdatedAt` (`Instant`), `blockTimeUpdatedSource` / `timeInServiceUpdatedSource` ("manual" | "flightlog"), `createdAt`. One airplane in the owner's fleet; at most 25 per user, at least 1.

### `service_timelines` (`ServiceTimeline`)
`id`, `item`, `isTitle` (boolean — title row vs item row), `description`, `cycle`, `lastDone`, `dueDate`, `timeLeft`, `timelineOrder` (Integer, display order **within one aircraft**), `user_id`, `aircraft_id`.

### `flight_logs` (`FlightLog`)
`id`, `fromAirport`, `toAirport`, `blockTimeIn`, `blockTimeOut`, `timeInServiceIn`, `timeInServiceOut` (all Double, nullable), `blockTimeStart`, `blockTimeEnd` (`Instant`, nullable — engine start/stop, UTC), `timeInServiceStart`, `timeInServiceEnd` (`Instant`, nullable — wheels-off/wheels-on, UTC), `source` (String "manual" | "csv" | "aeroapi", nullable — see `FlightLog.getEffectiveSource()` and `HoursService.recomputeChain`), `faFlightId` (String, nullable — set only when the row came from an accepted `FlightSuggestion`), `user_id`, `aircraft_id`. Hours math only ever looks at one aircraft's flights.

### `description_options` (`DescriptionOption`)
`id`, `option` (not null), `user_id`. Stores a user's custom Service Timeline description choices.

### `flight_suggestions` (`FlightSuggestion`)
`id`, `tailNumber`, `faFlightId` (unique per user), `departureTime`, `arrivalTime` (`Instant`), `origin`, `destination`, `status` (String "pending" | "accepted" | "dismissed"), `createdAt`, `user_id`, `aircraft_id` (the aircraft whose subscription found it). AeroAPI-detected flights awaiting a user decision — see `docs/ADSB_SYNC_SPEC.md`.

### `import_proposals` (`ImportProposal`)
`id`, `user_id` (not null), `status` ("OPEN" | "NEEDS_FIT_CONFIRMATION" | "CONFIRMED" | "REPLACED" | "EXPIRED"), `itemsJson` / `rejectedJson` (TEXT, JSON lists), `submittedCount`, `createdAt`, `expiresAt` (+24 h), `confirmedAt`, `connectionClientId`, `aircraft_id` (target aircraft; null after that aircraft is removed). An AI assistant's previewed timeline import; at most one OPEN/NEEDS_FIT_CONFIRMATION per user per aircraft.

### `timeline_import_records` (`TimelineImportRecord`)
`id`, `user_id` (not null), `proposalId`, `clientId`, `clientName`, `itemCount`, `importedAt`, `aircraftLabel` (tail number at import time, text). One row per confirmed import; shown on Connected assistants.

### `subscriptions` (`Subscription`)
`id`, `user_id` (not null, **no longer unique**), `aircraft_id` (unique — one flight-sync subscription per aircraft), `tailNumber`, `active`, `status`, `lastCheckedAt`, `lastSeenAt`, `pollIntervalDays`, `preferredCheckHour`. The old one-per-user unique constraint is dropped by `AircraftBackfill`.

### `oauth_authorizations` (`OAuthAuthorizationEntity`) — no `user_id`
String `id`, `registeredClientId` (assistant's CIMD URL), `principalName` (username), scopes, attributes (TEXT), and the authorization code / access token / refresh token **SHA-256 hashes** with issued/expires/metadata, plus `clientName`, `lastUsedAt`. One row = one connected assistant; deleting it disconnects. Keyed by username rather than FK because Spring Authorization Server works in principal names.

### `oauth_consents` (`OAuthConsentEntity`) — no `user_id`
Composite key (`registeredClientId`, `principalName`), `authorities`. Approved scopes per assistant per owner.

## Relationships
`User` 1—* `Aircraft` (1..25). `Aircraft` 1—* `ServiceTimeline`, `FlightLog`, `FlightSuggestion`; 0..1 `Subscription`. Child rows keep `user_id` too, so ownership checks never depend on a join; services keep `row.user == row.aircraft.user`. `User` 1—* `DescriptionOption`, alert settings and recipients stay account-wide. `User` 1—* `ServiceTimeline`, `FlightLog` are cascade ALL with JSON managed/back references.

## Boot-time backfill
`config/AircraftBackfill` runs before the seeders on every boot: it drops the old `subscriptions(user_id)` unique constraint and, for each user without an aircraft, creates one from the legacy `users` columns and sets `aircraft_id` on that user's rows where it is null. Idempotent; logs `Aircraft backfill: N users, M rows attached`. The `aircraft_id` columns are nullable in the schema only because `ddl-auto=update` can't add NOT NULL to a populated table.

## Repositories (Spring Data JPA)
- `UserRepository.findByUsername`
- `AircraftRepository.findByIdAndUser` (the only way to load a client-supplied aircraft id) / `findByUserOrderByIdAsc`
- `FlightLogRepository.findByAircraft`
- `ServiceTimelineRepository.findByAircraftOrderByTimelineOrderAsc` / `findMaxTimelineOrderByAircraft` (`@Query` for max order) / `findByUserOrderByAircraftIdAscTimelineOrderAsc` (whole fleet, for alerts and picker counts)
- `DescriptionOptionRepository.findByUser`

## Gotchas
- **Dates are strings.** `lastDone`, `dueDate`, `timeLeft`, `cycle` are all `String`, not typed dates/numbers — parsing/validation happens ad hoc in JS. A typed model would be more robust if this grows.
- **`timeLeft` is persisted** but is really a derived value (computed client-side). It can drift from the truth.
- **In-memory H2 locally** means no persistence between runs — easy to mistake for data loss bugs.
- **`ddl-auto=update`** on prod auto-migrates schema; convenient but risky (no review of destructive/ambiguous changes). Fine for a solo hobby app; revisit before real users.
