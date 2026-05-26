# Backend Map

> Covers `src/main/java/com/example/AviaryService/**` (except `entity/`, mapped in [DATABASE](DATABASE.md)). Update this file when you change controllers, security, or business logic.

## Layout
- `AviaryServiceApplication.java` — Spring Boot entry point. Nothing custom.
- `config/SecurityConfig.java` — security + password encoder + user lookup.
- `controllers/UserController.java` — **the entire web layer** (~476 lines, one class). No service layer; it calls repositories directly.
- `repositories/` — Spring Data JPA interfaces (see [DATABASE](DATABASE.md)).
- `entity/` — JPA entities + DTO (see [DATABASE](DATABASE.md)).

## Auth
`SecurityConfig`:
- `BCryptPasswordEncoder` bean.
- `UserDetailsService` looks up `UserRepository.findByUsername`, builds a Spring user with role `USER`. Throws `UsernameNotFoundException` if missing.
- Filter chain: `/register`, `/login`, `/css/**`, `/js/**`, `/images/**` are public; **everything else requires auth**. Form login at `/login`, success → `/dashboard`. Logout at `POST /logout` → `/login?logout`.
- CSRF is on (Spring default); the frontend sends the token from `<meta name="_csrf">`.

## Endpoints (all in UserController)
| Method | Path | Body / params | Returns | Notes |
|---|---|---|---|---|
| GET | `/register` | — | `register` view | |
| POST | `/register` | `username`, `password` | redirect `/login` | rejects duplicate username |
| GET | `/login` | — | `login` view | |
| GET | `/dashboard` | — | `dashboard` view | loads timelines, flight logs, hours, aircraft info for the auth user |
| POST | `/dashboard` | JSON timeline fields | row JSON or 302 | adds a Service Timeline row; assigns next `timelineOrder` |
| POST | `/update/{id}` | `TimelineUpdateDTO` | status JSON | partial update of a timeline row. Ownership checked (403 if not owner) |
| DELETE | `/delete/{id}` | — | 200 / 403 | deletes a timeline row. Ownership checked (403 if not owner) |
| POST | `/updateOrder` | `List<Long>` ids | void | reorders timelines; user-scoped, matches ids with `.equals` |
| POST | `/updateUserInfo` | JSON aircraft fields | status JSON | updates makeModel/tailNumber/ownerName/makeModelSN for auth user |
| POST | `/updateHours` | `hobbsTimeToAdd`/`tachTimeToAdd`/`newHobbsTime`/`newTachTime` | hours JSON | set-or-add Hobbs/Tach |
| GET | `/flightlogs` | — | `List<FlightLog>` JSON | auth user's logs |
| POST | `/addflightlog` | `FlightLog` JSON | log + new totals JSON | recomputes hours via `calculateMergedHours` |
| DELETE | `/deleteflightlog/{id}` | — | status + new totals | **ownership checked** (403 if not owner) |
| DELETE | `/deleteOption/{id}` | — | text | description option; **ownership checked** (403 if not owner) |

## Key logic
- **`calculateMergedHours(logs, useHobbs)`** — sums flight time across all logs by **merging overlapping intervals** so overlapping flights don't double-count. Hobbs uses `hobbsOut`/`hobbsIn`; Tach uses `tachIn`/`tachOut`. Result is written back to `User.hobbsHours`/`tachHours` on add/delete of a log.
- **`saveCustomDescriptionOption`** — persists a user's custom Service Timeline "Description" values so they reappear in the dropdown.
- **Hours "last updated" stamps:** `/updateHours` sets `hobbsUpdatedAt`/`tachUpdatedAt` (UTC `Instant`) + `*UpdatedSource = "manual"` for whichever value changed; `/addflightlog` and `/deleteflightlog` set both with source `"flightlog"`. `GET /dashboard` passes the 4 values (timestamps as ISO strings) to the view, which renders them in the browser's local time.

## Known issues
- **(FIXED 2026-05-25) IDOR / broken access control:** `/update/{id}` and `/delete/{id}` now load the authenticated user and return 403 unless `timeline.getUser().getId()` matches — same ownership pattern as `/deleteflightlog` and `/deleteOption`.
- **(FIXED 2026-05-25) `updateOrder` id comparison:** now uses `t.getId().equals(id)` instead of `==` (reference compare).
- **No regression test yet** for the ownership checks — worth adding (user A cannot mutate user B's rows → expect 403).
- **Debug logging:** many `System.out.println` calls (incl. a per-login timing print in `SecurityConfig`). Replace with the SLF4J `log` or remove.
- **No service layer:** business logic lives in the controller. Fine at this size; revisit if it grows.
