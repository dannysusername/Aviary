# Database Map

> Covers the JPA entities (`src/main/java/com/example/AviaryService/entity/**`) and datasource config. Update this file when you add/change an entity, column, relationship, or datasource setting.

## Engines
- **Local / default:** profile `h2` (`application.properties` → `spring.profiles.active: h2`). No datasource is configured, so Spring Boot auto-configures an **in-memory H2** database. **Data is lost on every restart.**
- **Production (Heroku):** profile `heroku` (`application-heroku.properties`). Uses Postgres via `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (Heroku sets these from `DATABASE_URL`). `ddl-auto=update` (Hibernate auto-creates/alters tables), Postgres dialect, `open-in-view=false`.
- There is a tracked file named `dbdata` at the repo root (purpose unconfirmed — verify whether it holds local DB state or config).

## Tables (entity → table)
All ids are `@GeneratedValue(IDENTITY)`. Every row belongs to a `User` via `user_id` (FK, not null).

### `users` (`User`)
`id`, `username` (unique, not null), `password` (BCrypt, not null), `blockTimeHours` (Double), `timeInServiceHours` (Double), `makeModel`, `tailNumber`, `ownerName`, `makeModelSN`, `blockTimeUpdatedAt` / `timeInServiceUpdatedAt` (`Instant`, UTC timestamp, nullable), `blockTimeUpdatedSource` / `timeInServiceUpdatedSource` (String "manual" | "flightlog", nullable), `blockTimeManualBaseline` / `timeInServiceManualBaseline` (Double, manually-set floor so log activity can't silently lower the displayed total), `aeroApiKey` (encrypted, see `AeroApiKeyConverter`).
One-to-many (cascade ALL) → `serviceTimeline`, `flightLogs`.

### `service_timelines` (`ServiceTimeline`)
`id`, `item`, `isTitle` (boolean — title row vs item row), `description`, `cycle`, `lastDone`, `dueDate`, `timeLeft`, `timelineOrder` (Integer, display order), `user_id`.

### `flight_logs` (`FlightLog`)
`id`, `fromAirport`, `toAirport`, `blockTimeIn`, `blockTimeOut`, `timeInServiceIn`, `timeInServiceOut` (all Double, nullable), `blockTimeStart`, `blockTimeEnd` (`Instant`, nullable — engine start/stop, UTC), `timeInServiceStart`, `timeInServiceEnd` (`Instant`, nullable — wheels-off/wheels-on, UTC), `source` (String "manual" | "csv" | "aeroapi", nullable — see `FlightLog.getEffectiveSource()` and `HoursService.recomputeChain`), `faFlightId` (String, nullable — set only when the row came from an accepted `FlightSuggestion`), `user_id`.

### `description_options` (`DescriptionOption`)
`id`, `option` (not null), `user_id`. Stores a user's custom Service Timeline description choices.

### `flight_suggestions` (`FlightSuggestion`)
`id`, `tailNumber`, `faFlightId` (unique per user), `departureTime`, `arrivalTime` (`Instant`), `origin`, `destination`, `status` (String "pending" | "accepted" | "dismissed"), `createdAt`, `user_id`. AeroAPI-detected flights awaiting a user decision — see `docs/ADSB_SYNC_SPEC.md`.

### `import_proposals` (`ImportProposal`)
`id`, `user_id` (not null), `status` ("OPEN" | "NEEDS_FIT_CONFIRMATION" | "CONFIRMED" | "REPLACED" | "EXPIRED"), `itemsJson` / `rejectedJson` (TEXT, JSON lists), `submittedCount`, `createdAt`, `expiresAt` (+24 h), `confirmedAt`, `connectionClientId`. An AI assistant's previewed timeline import; at most one OPEN/NEEDS_FIT_CONFIRMATION per user.

### `timeline_import_records` (`TimelineImportRecord`)
`id`, `user_id` (not null), `proposalId`, `clientId`, `clientName`, `itemCount`, `importedAt`. One row per confirmed import; shown on Connected assistants.

### `oauth_authorizations` (`OAuthAuthorizationEntity`) — no `user_id`
String `id`, `registeredClientId` (assistant's CIMD URL), `principalName` (username), scopes, attributes (TEXT), and the authorization code / access token / refresh token **SHA-256 hashes** with issued/expires/metadata, plus `clientName`, `lastUsedAt`. One row = one connected assistant; deleting it disconnects. Keyed by username rather than FK because Spring Authorization Server works in principal names.

### `oauth_consents` (`OAuthConsentEntity`) — no `user_id`
Composite key (`registeredClientId`, `principalName`), `authorities`. Approved scopes per assistant per owner.

## Relationships
`User` 1—* `ServiceTimeline`, `FlightLog` (both cascade ALL, JSON managed/back references to avoid serialization loops). `User` 1—* `DescriptionOption`, `FlightSuggestion` (FK only, no mapped collection on `User` — accessed via repository query).

## Repositories (Spring Data JPA)
- `UserRepository.findByUsername`
- `FlightLogRepository.findByUser`
- `ServiceTimelineRepository.findByUserOrderByTimelineOrderAsc` / `findByUserOrderByIdAsc` / `findMaxTimelineOrderByUser` (`@Query` for max order)
- `DescriptionOptionRepository.findByUser`

## Gotchas
- **Dates are strings.** `lastDone`, `dueDate`, `timeLeft`, `cycle` are all `String`, not typed dates/numbers — parsing/validation happens ad hoc in JS. A typed model would be more robust if this grows.
- **`timeLeft` is persisted** but is really a derived value (computed client-side). It can drift from the truth.
- **In-memory H2 locally** means no persistence between runs — easy to mistake for data loss bugs.
- **`ddl-auto=update`** on prod auto-migrates schema; convenient but risky (no review of destructive/ambiguous changes). Fine for a solo hobby app; revisit before real users.
