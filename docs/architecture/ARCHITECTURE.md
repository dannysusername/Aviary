# AviaryService — Architecture Map (index)

> Map set: [BACKEND](BACKEND.md) · [FRONTEND](FRONTEND.md) · [DATABASE](DATABASE.md) · [BUILD_AND_DEPLOY](BUILD_AND_DEPLOY.md)
> Keep these current: when you change code in an area, update that area's map in the same change. The pre-commit guard warns if you forget.

## What it is
A single-aircraft maintenance + hours tracker for a private pilot / aircraft owner. Spring Boot server-rendered app (Thymeleaf) with a thin AJAX layer. One user = one aircraft.

## Stack
Spring Boot 3.4.3 · Java 21 · Spring MVC + Security + Data JPA · Thymeleaf · Apache POI · H2 (local) / Postgres (prod) · Gradle · Heroku.

## Shape (monolith)
- **One controller** (`UserController`) holds every route. No service layer; controllers talk to repositories directly.
- **4 JPA entities** → 4 tables, all scoped to a `User`.
- **3 Thymeleaf pages**: `login`, `register`, `dashboard`. The dashboard is the whole app (two tabs: Service Timeline + Log Book).

## Request flow (a typical dashboard edit)
1. Browser loads `GET /dashboard` → `UserController` fetches the user's timelines, flight logs, hours, aircraft info → Thymeleaf renders `dashboard.html`.
2. User edits a cell → `dashboard.js` debounces and `POST`s JSON via axios (with the Spring CSRF token from `<meta>`) to an endpoint like `/update/{id}`.
3. Controller updates the entity via the repository and returns JSON.
4. "Time Left" is computed **client-side** from the due date + current Tach hours.

## Cross-cutting concerns
- **Auth:** Spring Security form login, BCrypt passwords, single `USER` role. See [BACKEND](BACKEND.md#auth).
- **Per-user scoping:** every entity has a `user_id`; queries filter by the authenticated user. See [DATABASE](DATABASE.md).
- **Hours model:** flight logs roll up into `User.hobbsHours` / `tachHours`, which maintenance due-dates count against.

## Known issues (high level — details in the area maps)
- Local dev uses **in-memory H2** (data is lost on restart). See [DATABASE](DATABASE.md).
- Heroku `Procfile` hardcodes the jar version; bumping the project version breaks the boot command. See [BUILD_AND_DEPLOY](BUILD_AND_DEPLOY.md).
- Debug `System.out.println` calls litter the controller.
- **Fixed 2026-05-25:** IDOR on `/update/{id}` + `/delete/{id}` (now ownership-checked) and the `updateOrder` `==` reference-compare bug.
