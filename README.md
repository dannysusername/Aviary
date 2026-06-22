# AviaryService

**AviaryService is live — just open it in your browser:**

### 👉 https://aviarist-d300b0c36379.herokuapp.com/login

No install, no setup, nothing to download. Register a username + password and you're in. Everything below is for people who want to *develop* AviaryService; if you just want to *use* it, the link above is all you need.

---

## What it is

A multi-user maintenance and flight-hours tracker for general-aviation aircraft owners:

- **Service timeline** — track every recurring maintenance item (annual, transponder/pitot-static tests, VOR checks, oil changes, etc.) on a **calendar cycle** (days/months/years) *or* an **engine-hours cycle**. Each row shows last-done, next-due, and live "time left," and a one-click **Complete Maintenance** button rolls the dates and hours forward automatically.
- **Hours tracking** — Hobbs and Tach meters with a manual baseline "floor," so log-book activity can only ever raise the displayed hours, never silently lower them.
- **Log book** — record flights by hand (from/to airport, Hobbs/Tach out and in) **or import a Garmin avionics CSV** and let the app derive block time (oil pressure > 15 psi) and airborne time (groundspeed > 35 kt) for you.
- **Aircraft info card** — make/model, tail number, owner, and serial number, editable inline.

Each account is fully isolated — its own aircraft, service timeline, flight logs, and custom maintenance descriptions. Ownership is enforced server-side on every write.

## Using it

1. **Register** at the link above (or **log in**).
2. **Fill in your aircraft info** — make/model, tail number, owner, serial.
3. **Build your service timeline** — add maintenance items with a calendar or hours cycle. Rows are drag-reorderable, and you can insert title rows to group them.
4. **Set your hours** — enter current Hobbs and Tach manually, or let the log book keep them current.
5. **Log flights** — add entries by hand, or upload a Garmin CSV and AviaryService computes the times and updates your meters.

## Tech stack

Spring Boot 3.4.3 on Java 21, server-rendered with Thymeleaf, secured with Spring Security (BCrypt, form login). Data access is Spring Data JPA / Hibernate. **H2 in-memory** locally, **PostgreSQL** in production — selected by Spring profile. Built with Gradle.

---

## Local development

AviaryService is a single Spring Boot app. The active profile decides the database:

| Profile | Database | Use |
|---|---|---|
| `h2` *(default)* | In-memory H2, reseeded on every boot | Local dev — zero setup |
| `main` | Local PostgreSQL (`jdbc:postgresql://localhost:5432/AviaryService`) | Local dev against a real DB |
| `heroku` | Postgres from Heroku's `SPRING_DATASOURCE_*` vars | Production |

The default profile (`spring.profiles.active=h2`) is set in `application.properties`, so a fresh clone runs with no database to install.

**Run the server:**

```bash
./gradlew bootRun
```

Then open `http://localhost:8080`. The seeded H2 profile ships two logins you can use immediately:

| Username | Password |
|---|---|
| `DanielIbarra` | `DI` |
| `TomasIbarra` | `TI` |

> **Live frontend edits:** plain `bootRun` serves *build-time copies* of `static/` and `templates/`, so CSS/HTML edits won't show until you rebuild. To edit and refresh without restarting, run with the source folders wired in and Thymeleaf caching off:
>
> ```bash
> ./gradlew bootRun --args='--spring.web.resources.static-locations=file:src/main/resources/static/ --spring.thymeleaf.prefix=file:src/main/resources/templates/ --spring.thymeleaf.cache=false --spring.web.resources.cache.period=0'
> ```
>
> Java changes still need a recompile — Spring Boot DevTools (already on the classpath) restarts the app when the classes rebuild. Run `./gradlew -t classes` in a second terminal for automatic recompiles.

**Tests:**

```bash
./gradlew test          # fast unit/integration suite (JUnit)
./gradlew browserTest   # full-stack Playwright suite via real Chromium (slow, opt-in)
```

The Playwright suite lives in its own `browserTest` source set so it doesn't slow down `./gradlew test`. First run downloads ~150 MB of browser binaries.

## Deployment & CI/CD

CI lives in `.github/workflows/ci.yml` and behaves differently per branch:

```
push to a feature branch (e.g. newfeature)
  → build + run the full unit test suite

push to main
  → build, then deploy to the STAGING Heroku app (aviarist-staging)
```

- **Staging** (`aviarist-staging`) is deployed on every push to `main`, authenticated with the `HEROKU_API_KEY` GitHub Actions secret.
- **Production** (`aviarist-d300b0c36379`, the link at the top) is a separate app, promoted from staging deliberately once it looks good.
- Heroku runs the app from the `Procfile` (`java -jar build/libs/AviaryService-0.0.1-SNAPSHOT.jar`) on Java 21 (`system.properties`). Schema changes apply via Hibernate `ddl-auto=update`.

## Architecture

The app is a small, conventional Spring MVC project under `src/main/java/com/example/AviaryService/`:

- **`controllers/UserController.java`** — every route: auth pages, the dashboard, timeline CRUD + reordering, hours updates, flight-log CRUD, the Garmin CSV parser, and "Complete Maintenance."
- **`entity/`** — `User`, `ServiceTimeline`, `FlightLog`, `DescriptionOption` (+ a `DTO/` for timeline updates).
- **`repositories/`** — Spring Data JPA repositories, all scoped by user.
- **`config/`** — `SecurityConfig` (login/logout, BCrypt, per-route access) and `DataSeeder` (the H2 demo data).
- **`resources/templates/`** + **`resources/static/`** — Thymeleaf views and the dashboard's CSS/JS.

Deeper design docs live in **[docs/](docs/)** — see `ARCHITECTURE.md`, `BACKEND.md`, `FRONTEND.md`, `DATABASE.md`, and `BUILD_AND_DEPLOY.md`. A **Share/Export** feature (print / PDF / email / text) is specced in `docs/SHARE_EXPORT_SPEC.md` but not yet built.
