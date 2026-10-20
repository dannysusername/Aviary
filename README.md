# Aviary

**Aviary is live — just open it in your browser:**

### 👉 https://aviary.danielibarra.com

No install, no setup, nothing to download. Register a username + password and you're in. Everything below is for people who want to *develop* Aviary; if you just want to *use* it, the link above is all you need.

---

## What it is

A multi-user maintenance and flight-hours tracker for general-aviation aircraft owners:

- **Service timeline** — track every recurring maintenance item (annual, transponder/pitot-static tests, VOR checks, oil changes, etc.) on a **calendar cycle** (days/months/years) *or* an **engine-hours cycle**. Each row shows last-done, next-due, and live "time left," and a one-click **Complete Maintenance** button rolls the dates and hours forward automatically.
- **Hours tracking** — block time and time in service, each with a manual baseline "floor," so log-book activity can only ever raise the displayed hours, never silently lower them.
- **Log book** — record flights by hand (from/to airport, block time and time in service out and in) **or import a Garmin avionics CSV** and let the app derive block time (oil pressure > 15 psi) and airborne time (groundspeed > 35 kt) for you.
- **Aircraft info card** — make/model, tail number, owner, and serial number, editable inline.

Each account is fully isolated — its own aircraft, service timeline, flight logs, and custom maintenance descriptions. Ownership is enforced server-side on every write.

## Using it

1. **Register** at the link above (or **log in**).
2. **Fill in your aircraft info** — make/model, tail number, owner, serial.
3. **Build your service timeline** — add maintenance items with a calendar or hours cycle. Rows are drag-reorderable, and you can insert title rows to group them.
4. **Set your hours** — enter current block time and time in service manually, or let the log book keep them current.
5. **Log flights** — add entries by hand, or upload a Garmin CSV and Aviary computes the times and updates your meters.

## Connect an AI assistant

Aviary is also an MCP server, so an AI assistant can import your existing maintenance records — a spreadsheet, a typed list, another tracker's export — into your service timeline.

The easiest way in is the **Import with AI** button on the dashboard — it shows the address with a Copy button and these same steps.

**Connect (one time):**

1. In your AI assistant, add a **custom connector** (also called a **remote MCP server**).
2. Paste the address: `https://aviary.danielibarra.com/mcp`
3. When the assistant asks you to sign in, log in to Aviary and click **Allow**.

You sign in on Aviary's own login page; the assistant never sees your password. The assistant must support remote MCP servers with OAuth sign-in.

**Import:**

1. Give the assistant your records — paste a list or attach a spreadsheet — and ask it to import them into Aviary.
2. It shows you a **proposal** first: the items it will add, anything flagged for you to check (unclear dates, no due date, items you already have), and any rows it couldn't use and why.
3. Tell it to go ahead. The items are added to the bottom of your service timeline. Nothing is added before you confirm, and existing items are never changed or deleted.

Any row with a name can be imported — including on-condition items with no dates. Values that can't be used are left blank with a note.

**Disconnect:** open **Import with AI** on the dashboard; your connected assistants are listed with a **Disconnect** button, plus what each one imported. A disconnected assistant is cut off on its next request.

---

## Local development

Aviary is a single Spring Boot 3.4.3 app on Java 21: server-rendered with Thymeleaf, secured with Spring Security (BCrypt, form login), data access through Spring Data JPA / Hibernate, built with Gradle. The active profile decides the database — **H2 in-memory** locally, **PostgreSQL** in production:

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
- **Production** (Heroku app `aviarist-d300b0c36379`, served at the link at the top) is a separate app, promoted from staging deliberately once it looks good.
- Heroku runs the app from the `Procfile` (`java -jar build/libs/AviaryService-0.0.1-SNAPSHOT.jar`) on Java 21 (`system.properties`). Schema changes apply via Hibernate `ddl-auto=update`.

## MCP server (developers)

`/mcp` is an [MCP](https://modelcontextprotocol.io) server (Spring AI, Streamable HTTP). Aviary is also its own OAuth 2.1 authorization server, so assistants sign in as an owner.

| | |
|---|---|
| Endpoint | `POST /mcp` — needs `Authorization: Bearer <token>`; without one you get `401` with `WWW-Authenticate: Bearer resource_metadata=…` |
| Discovery | `/.well-known/oauth-protected-resource`, `/.well-known/oauth-authorization-server` |
| Sign-in | Authorization code + PKCE (S256). Clients identify with a **Client ID Metadata Document** (an https `client_id` URL); Dynamic Client Registration is off |
| Tokens | Opaque, 7 days, stored SHA-256 hashed; checked against the database on every call, so Disconnect is immediate |
| Scopes | `aviary.read`, `aviary.timeline.import` |

**Tools** (each acts only on the signed-in owner's data):

| Tool | What it does |
|---|---|
| `get_import_format` | Item fields, rules, and an example |
| `list_service_timeline` | The owner's current items |
| `propose_timeline_import` | Validates items into a proposal (24 h); changes nothing |
| `continue_poor_fit` | Continues a proposal where most rows didn't fit, after the owner agrees |
| `confirm_timeline_import` | Adds exactly the proposal's items, all or nothing |
| `list_flights` | The owner's logged flights |

**Config:** `aviary.oauth.issuer` (env `AVIARY_OAUTH_ISSUER`) is the site's public URL; the MCP address is that plus `/mcp`. It defaults to `http://localhost:8080` locally and `https://aviary.danielibarra.com` on Heroku — set it on staging to the staging URL, or sign-in there points at production.

**Try it locally:** run `./gradlew bootRun`, then add `http://localhost:8080/mcp` as a remote MCP server in an MCP client (or use the [MCP Inspector](https://github.com/modelcontextprotocol/inspector)) and sign in with a seeded account.

## Architecture

The app is a small, conventional Spring MVC project under `src/main/java/com/example/AviaryService/`:

- **`controllers/UserController.java`** — every route: auth pages, the dashboard, timeline CRUD + reordering, hours updates, flight-log CRUD, the Garmin CSV parser, and "Complete Maintenance."
- **`entity/`** — `User`, `ServiceTimeline`, `FlightLog`, `DescriptionOption` (+ a `DTO/` for timeline updates).
- **`repositories/`** — Spring Data JPA repositories, all scoped by user.
- **`config/`** — `SecurityConfig` (login/logout, BCrypt, per-route access, the `/mcp` bearer-token chain) and `DataSeeder` (the H2 demo data).
- **`oauth/`** — the OAuth authorization server for MCP sign-in (client metadata documents, hashed token storage, token checks).
- **`mcp/`** — MCP tools; **`services/imports/`** + **`util/TimelineImportRules`** — import proposals, confirm, and the Import with AI page's connections.
- **`resources/templates/`** + **`resources/static/`** — Thymeleaf views and the dashboard's CSS/JS.

Deeper design docs live in **[docs/](docs/)** — see `ARCHITECTURE.md`, `BACKEND.md`, `FRONTEND.md`, `DATABASE.md`, and `BUILD_AND_DEPLOY.md`. A **Share/Export** feature (print / PDF / email / text) is specced in `docs/specs/SHARE_EXPORT_SPEC.md` but not yet built.
