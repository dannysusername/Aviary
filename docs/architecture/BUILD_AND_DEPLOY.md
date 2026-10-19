# Build & Deploy Map

> Covers `build.gradle`, `settings.gradle`, `system.properties`, `Procfile`, `application*.properties`, and `.github/workflows/`. Update this file when you change dependencies, build config, CI, or deploy settings.

## Build (Gradle)
- Spring Boot 3.4.3, Java 21 (Gradle toolchain), Spring dependency management plugin.
- Wrapper: use `./gradlew` (don't rely on a system Gradle).
- Commands:
  - `./gradlew build` — compile + test, produces `build/libs/AviaryService-0.0.1-SNAPSHOT.jar`
  - `./gradlew bootRun` — run locally (`http://localhost:8080`)
  - `./gradlew test` — JUnit 5 suite
  - `./gradlew browserTest` — Playwright suite in `src/browserTest` (real Chromium, slow; not run in CI). `BrowserTestBase.setLastDone/setDueDate` split a combined "date hours" string into the entity's separate columns.
- Key deps: `spring-boot-starter-{data-jpa,security,web,thymeleaf}`, `jackson-databind`, `com.h2database:h2`, `org.apache.poi:poi:5.4.1`, `org.postgresql:postgresql` (runtimeOnly). Tests: H2, `spring-boot-starter-test`, `spring-security-test`, Mockito.

## Config profiles
- `application.properties` → activates profile `h2` (in-memory H2 locally; see [DATABASE](DATABASE.md)).
- `application-main.properties` → local Postgres (`localhost:5432/AviaryService`) for testing against a real DB. Run with `./gradlew bootRun --args='--spring.profiles.active=main'`.
- `application-heroku.properties` → Postgres + `ddl-auto=update` + `server.port=${PORT:8080}`. Also `server.forward-headers-strategy=native` (trust Heroku's `X-Forwarded-Proto` so requests count as HTTPS → Secure cookies + HSTS) and `server.servlet.session.cookie.secure=true`. Heroku must have `SPRING_PROFILES_ACTIVE=heroku` (otherwise the app falls back to the in-memory `h2` profile and loses data on restart) and `AVIARY_REMEMBER_ME_KEY` (otherwise remember-me tokens are signed with the public dev fallback).
- `src/test/resources/application-tests.yml` + `application.properties` → the `test` profile used by `@ActiveProfiles("test")`.
- `system.properties` → `java.runtime.version=21` (pins the JDK on Heroku).

## Local setup: `.env` (required)
`application.properties` imports an optional, gitignored `.env` from the project root. It must hold `AVIARY_AERO_KEY`, a Base64 AES-256 key (32 random bytes), or `AeroKeyHolder` throws `Illegal base64 character 2d` at startup (the committed fallback isn't valid Base64). This applies to every profile. `.env` doesn't sync between machines, so each new checkout needs one:
```
# Mac/Linux/Git Bash
echo "AVIARY_AERO_KEY=$(openssl rand -base64 32)" > .env
```
Data encrypted with one machine's key can't be decrypted with another's. To share a local database between machines, copy the same `.env` to each. Heroku needs its own `AVIARY_AERO_KEY` config var.

## Run target (Heroku)
`Procfile`: `web: java -jar build/libs/AviaryService-0.0.1-SNAPSHOT.jar`.

## CI/CD (`.github/workflows/ci.yml`)
Triggers: push to `newfeature` or `main`, and PRs to `main`.
- **`feature-build-and-test`** (any ref except `main`, incl. PRs): cache Gradle, JDK 21 (Zulu), `./gradlew build -x test`, then `./gradlew test`. So **tests run on feature branches and on PRs to main**.
- **`main-build-and-deploy`** (ref == `main`): build `-x test`, then deploy to Heroku app **`aviarist-staging`** by pushing to its git remote, authed by the `HEROKU_API_KEY` GitHub secret.

## Gotchas
- **Tests are skipped on the `main` deploy job** (`build -x test`, no `test` step). The safety net is the PR test run before merge; a direct push to `main` deploys without running tests.
- **`Procfile` hardcodes the jar version** (`...-0.0.1-SNAPSHOT.jar`). If you bump `version` in `build.gradle`, the jar name changes and Heroku boot breaks. Keep them in sync (or make the Procfile version-agnostic).
- Deploy goes to **staging only** (`aviarist-staging`) — there is no automated production deploy in this workflow.
- `application.properties` uses `key: value` (colon) style; valid for Java `.properties`, but `=` is the more conventional form.
