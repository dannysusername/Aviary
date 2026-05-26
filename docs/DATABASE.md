# Database Map

> Covers the JPA entities (`src/main/java/com/example/AviaryService/entity/**`) and datasource config. Update this file when you add/change an entity, column, relationship, or datasource setting.

## Engines
- **Local / default:** profile `h2` (`application.properties` → `spring.profiles.active: h2`). No datasource is configured, so Spring Boot auto-configures an **in-memory H2** database. **Data is lost on every restart.**
- **Production (Heroku):** profile `heroku` (`application-heroku.properties`). Uses Postgres via `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (Heroku sets these from `DATABASE_URL`). `ddl-auto=update` (Hibernate auto-creates/alters tables), Postgres dialect, `open-in-view=false`.
- There is a tracked file named `dbdata` at the repo root (purpose unconfirmed — verify whether it holds local DB state or config).

## Tables (entity → table)
All ids are `@GeneratedValue(IDENTITY)`. Every row belongs to a `User` via `user_id` (FK, not null).

### `users` (`User`)
`id`, `username` (unique, not null), `password` (BCrypt, not null), `hobbsHours` (Double), `tachHours` (Double), `makeModel`, `tailNumber`, `ownerName`, `makeModelSN`, `hobbsUpdatedAt` / `tachUpdatedAt` (`Instant`, UTC timestamp, nullable), `hobbsUpdatedSource` / `tachUpdatedSource` (String "manual" | "flightlog", nullable).
One-to-many (cascade ALL) → `serviceTimeline`, `flightLogs`.

### `service_timelines` (`ServiceTimeline`)
`id`, `item`, `isTitle` (boolean — title row vs item row), `description`, `cycle`, `lastDone`, `dueDate`, `timeLeft`, `timelineOrder` (Integer, display order), `user_id`.

### `flight_logs` (`FlightLog`)
`id`, `fromAirport`, `toAirport`, `hobbsIn`, `hobbsOut`, `tachIn`, `tachOut` (all Double, nullable), `user_id`.

### `description_options` (`DescriptionOption`)
`id`, `option` (not null), `user_id`. Stores a user's custom Service Timeline description choices.

## Relationships
`User` 1—* `ServiceTimeline`, `FlightLog` (both cascade ALL, JSON managed/back references to avoid serialization loops). `User` 1—* `DescriptionOption`.

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
