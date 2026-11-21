# Backend Map

> Covers `src/main/java/com/example/AviaryService/**` (except `entity/`, mapped in [DATABASE](DATABASE.md)). Update this file when you change controllers, security, or business logic.

## Layout
- `AviaryServiceApplication.java` — Spring Boot entry point. Nothing custom.
- `config/SecurityConfig.java` — security + password encoder + user lookup.
- `controllers/UserController.java` — **the entire web layer** (~476 lines, one class). No service layer; it calls repositories directly.
- `controllers/GlobalExceptionHandler.java` — `@RestControllerAdvice`: `RateLimitedException` → 429, `DataIntegrityViolationException` (e.g. text past a column's length) → 400. Bodies carry both `error` and `message` keys.
- `controllers/AlertPublicController.java` — emailed confirm/decline/unsubscribe links (see Auth).
- `services/LoginAttemptService.java`, `services/OutboundEmailLimiter.java` — rate limits (see "Input limits & rate limits").
- `util/Validation.java` (`maxLength`, `MAX_TEXT` = 255) and `util/SlidingWindowLimiter.java` (in-memory per-key rolling-window counter).
- `repositories/` — Spring Data JPA interfaces (see [DATABASE](DATABASE.md)).
- `entity/` — JPA entities + DTO (see [DATABASE](DATABASE.md)).

## Auth
`SecurityConfig`:
- `BCryptPasswordEncoder` bean.
- `UserDetailsService` looks up `UserRepository.findByUsername`, builds a Spring user with role `USER`. Throws `UsernameNotFoundException` if missing.
- Filter chains, in order: (0) OAuth authorization server endpoints (`oauth/AuthorizationServerConfig`), (1) `/mcp` bearer-token resource server, (2) static assets, (3) everything else. In the main chain `/register`, `/login`, `/css/**`, `/js/**`, `/images/**`, `/.well-known/oauth-protected-resource` are public; **everything else requires auth**. Form login at `/login`, success → `/dashboard` (or back to `/oauth2/authorize` when the login interrupted an assistant's sign-in). Logout at `POST /logout` → `/login?logout`.
- CSRF is on (Spring default); the frontend sends the token from `<meta name="_csrf">`. Exempt: `POST /alerts/confirm|decline|unsubscribe` (their unguessable token is the auth).
- **Login lockout:** `LoginAttemptService` counts wrong passwords per username (5 in 15 min). `UserDetailsService` then builds the user with `accountLocked(true)`, so Spring refuses before checking the password; a custom failure handler sends a `LockedException` to `/login?locked` (other failures → `/login?error`). A successful login clears the count. In memory: resets on restart, per dyno.
- **Emailed alert links:** `GET /alerts/confirm|decline|unsubscribe?token=` only renders a one-button page; the button POSTs to the same path to change state. Mail scanners open every link, so a state-changing GET would confirm/unsubscribe people on its own.

## Endpoints (all in UserController)
| Method | Path | Body / params | Returns | Notes |
|---|---|---|---|---|
| GET | `/register` | — | `register` view | |
| POST | `/register` | `username`, `password` | redirect `/login` | trims username; requires username 3-50 chars `[A-Za-z0-9._-]`, password 8-72 chars; rejects duplicates |
| GET | `/login` | — | `login` view | |
| GET | `/dashboard` | — | `dashboard` view | loads Status Sheet items, flight logs, hours, aircraft info for the auth user |
| POST | `/dashboard` | JSON Status Sheet fields | row JSON or 302 | adds a Status Sheet row; assigns next `statusSheetOrder` |
| POST | `/update/{id}` | `StatusSheetItemUpdateDTO` (+ `dueDateTouched`, `dueHoursTouched`) | saved row JSON: `lastDoneDate`, `lastDoneHours`, `dueDateDate`, `dueDateHours`, `timeLeft`, `warnings` | update of a Status Sheet row. Ownership checked (403 if not owner). Auto-fills the Due Date from Last Done + cycle (spec 007, `DueDateAutoFill`); hours must be numbers (400 "Hours must be a number, e.g. 25.0"). Both touched flags missing = older dashboard.js, saved as sent. |
| GET | `/statusSheet/{id}/cyclePreview` | — | `{dueDateDate, dueDateHours, canReset, reason}` | what "Reset to cycle" would set (Last Done + cycle). Ownership checked (403). |
| POST | `/resetDueToCycle/{id}` | — | saved row JSON (same as `/update`) | sets Due Date = Last Done + cycle, keeps Last Done, marks it auto again. 400 with the reason when there is nothing to reset. Ownership checked (403). |
| GET | `/statusSheet/{id}/completePreview?doneDate=&doneHours=` | — | `{hasCalendar, hasHours, doneDate, doneHours, nextDueDate, nextDueHours, expectedDueDate, expectedDueHours, canSave, reason}` | spec 008: what the "When was this done?" box starts with (Due Date → Last Done + cycle → today; hours: Due hours → Last Done hours + cycle → empty) and the next Due Date for edited values. Ownership checked (403). |
| POST | `/completeMaintenance/{id}` | `{doneDate, doneHours, expectedDueDate, expectedDueHours}` | saved row JSON | spec 008: Last Done = done date / hours, Due = + cycle (only parts with a cycle and a value). **409** "This row changed. Open it again to review." if the stored Due Date differs from `expected*` (double-click, other tab). Empty body = older page: old today-based behavior. |
| DELETE | `/delete/{id}` | — | 200 / 403 / 404 | deletes a Status Sheet row. Ownership checked (403 if not owner) |
| POST | `/updateOrder` | `List<Long>` ids | void | reorders Status Sheet items; user-scoped, matches ids with `.equals` |
| POST | `/updateUserInfo` | JSON aircraft fields | status JSON | updates makeModel/tailNumber/ownerName/makeModelSN for auth user |
| POST | `/updateHours` | `hobbsTimeToAdd`/`timeInServiceToAdd`/`newHobbsTime`/`newTimeInService` | hours JSON | set-or-add Hobbs time / time in service. 400 for NaN/Infinity, a negative set value, or an add that would go below 0 (checked before anything changes) |
| GET | `/flightlogs` | — | `List<FlightLog>` JSON | auth user's logs |
| POST | `/addflightlog` | `FlightLog` JSON | log + new totals JSON | recomputes hours via `calculateMergedHours` |
| DELETE | `/deleteflightlog/{id}` | — | status + new totals | **ownership checked** (403 if not owner) |
| DELETE | `/deleteOption/{id}` | — | text | description option; **ownership checked** (403 if not owner) |

## Key logic
- **`calculateMergedHours(logs, useHobbsTime)`** — sums flight time across all logs by **merging overlapping intervals** so overlapping flights don't double-count. Hobbs time uses `hobbsTimeOut`/`hobbsTimeIn`; time in service uses `timeInServiceOut`/`timeInServiceIn`. Result is written back to `User.hobbsTimeHours`/`timeInServiceHours` on add/delete of a log.
- **`saveCustomDescriptionOption`** — persists a user's custom Status Sheet "Description" values so they reappear in the dropdown.
- **Hours "last updated" stamps:** `/updateHours` sets `hobbsTimeUpdatedAt`/`timeInServiceUpdatedAt` (UTC `Instant`) + `*UpdatedSource = "manual"` for whichever value changed; `/addflightlog` and `/deleteflightlog` set both with source `"flightlog"`. `GET /dashboard` passes the 4 values (timestamps as ISO strings) to the view, which renders them in the browser's local time.

## Time Left + PDF
- `DueDates.formatTimeLeft(dueDate, dueHours, today, currentTimeInService)` is the one server-side Time Left formatter (smart format, see [FRONTEND](FRONTEND.md)). Used by `PdfExportService` (computed at render time — the stored `timeLeft` column goes stale) and `/completeMaintenance/{id}`.
- `DueDateAutoFill` (spec 007) decides the Due Date to save when Last Done or the cycle changes: date = `DueDates.nextDueDate` (same as Complete Maintenance, exact day), hours = Last Done + cycle hours. Owner-typed Due Dates are kept (`dueDateAuto` / `dueHoursAuto` = FALSE). Also the one hours formatter for Last Done / Due Date (`formatHours`: 1–3 decimals, `25` → `25.0`). Unit tests: `DueDateAutoFillTest`; endpoint tests: `StatusSheetAutoFillTest`.
- `GET /pdf?today=yyyy-MM-dd` — optional browser date, trusted only within ±1 day of the server date. PDF includes a Date column for flights (UTC date of block/service start) and prints hours with at least one decimal.

## Local demo data
`config/DemoDataSeeder` (`@Profile({"h2","main"})`, never Heroku/tests) creates **demo / Demo-pass-123** once: section titles, items due at every Time Left scale, 6 chained flight logs, 3 pending + 1 dismissed AeroAPI suggestions. Delete the `demo` user to regenerate with fresh relative dates.

## MCP server + AI assistant sign-in
Aviary is an MCP server at `POST /mcp` (Spring AI, Streamable HTTP, SYNC) and its own OAuth authorization server, so AI assistants sign in as an owner.
- **Sign-in:** authorization code + PKCE (S256 only), public clients only. Assistants identify with a **Client ID Metadata Document**: `client_id` is an https URL that `CimdFetcher` downloads (SSRF-guarded: https, no IP literals, no private/loopback/link-local DNS, no redirects, 5 KB, 5 s) and `CimdRegisteredClientRepository` turns into a client (cached 1 h). Dynamic Client Registration is off. Loopback redirect URIs may use any port (`AuthorizationServerConfig.validateRedirectUri`). Owner approves on `/oauth2/consent` (`ConsentController`).
- **Tokens:** opaque, 7 days, no refresh tokens (Spring Authorization Server 1.4 gives public clients none). Stored by `JpaOAuth2AuthorizationService` in `oauth_authorizations` with token values **SHA-256 hashed**. `LocalOpaqueTokenIntrospector` checks every `/mcp` call against that table, so **Disconnect** (`/account/connections`, deletes the row) cuts an assistant off on its next request. No/dead token → 401 with `WWW-Authenticate: Bearer resource_metadata=…`; token without `aviary.read` → 403.
- **Tools** (`mcp/`): `list_flights`, `get_import_format`, `list_status_sheet`, `propose_status_sheet_import`, `continue_poor_fit`, `confirm_status_sheet_import`. Each checks its scope and gets the owner from `McpCurrentUser` (the token's user — never a parameter).
- **Import:** `StatusSheetImportService` + pure `util/StatusSheetImportRules`. Propose stores an `import_proposals` row (24 h, one open per owner) and changes nothing; confirm adds exactly the proposed rows after the existing ones in one transaction (all or nothing), never edits existing rows, skips name matches unless the owner opts in, and writes a `timeline_import_records` row. Rate limits: 30 proposals / 10 confirms per owner per hour.

## Input limits & rate limits
- **Text length:** every text column is varchar(255). `Validation.maxLength` checks item/description/aircraft fields/airports/description options up front and returns a 400 with a readable message; `GlobalExceptionHandler` catches anything missed. The AeroAPI key is capped at 100 chars (it's stored encrypted, which roughly doubles its length).
- **Outbound email:** `OutboundEmailLimiter` allows 10 user-triggered emails per user per hour — alert recipient invites/resends and `POST /pdf/email` — then 429. Scheduled digests and "Send alert now" aren't counted (the latter has per-recipient limits in `sendDigest`). Max 10 alert recipients per user.
- **Bad JSON types** on `/subscription/settings` → 400 (was a 500 ClassCastException).
- Unexpected exceptions in `/update/{id}` and `/updateHours` are logged and return a generic message instead of `e.getMessage()`.

## Known issues
- **(FIXED 2026-05-25) IDOR / broken access control:** `/update/{id}` and `/delete/{id}` now load the authenticated user and return 403 unless `Status Sheet.getUser().getId()` matches — same ownership pattern as `/deleteflightlog` and `/deleteOption`.
- **(FIXED 2026-05-25) `updateOrder` id comparison:** now uses `t.getId().equals(id)` instead of `==` (reference compare).
- **No regression test yet** for the ownership checks — worth adding (user A cannot mutate user B's rows → expect 403).
- **Debug logging:** many `System.out.println` calls (incl. a per-login timing print in `SecurityConfig`). Replace with the SLF4J `log` or remove.
- **No service layer:** business logic lives in the controller. Fine at this size; revisit if it grows.
