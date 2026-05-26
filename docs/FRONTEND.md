# Frontend Map

> Covers `src/main/resources/templates/**` and `src/main/resources/static/**`. Update this file when you change a template, stylesheet, or JS behavior.

## Pages (Thymeleaf)
- `templates/login.html` + `static/css/loginstyle.css` + `static/js/signin.js` (password eye toggle).
- `templates/register.html` + `static/css/registerstyle.css`.
- `templates/dashboard.html` + `static/css/dashboardstyle.css` + `static/js/dashboard.js` — the whole app.

## Theme
Each stylesheet defines the same CSS-variable palette (`--primary` green `#2C9B6F`, `--accent` pink `#E64E6D`, surfaces, radius, shadows). Font: Inter via Google Fonts.

## Dashboard structure
- Fixed header (logo + logout).
- "My Hours" card: shows Hobbs/Tach, each with a small muted "last updated" line under it (`.hours-block` / `.hours-updated`) — local date + time + relative ("2 days ago") + source ("you edited it" / "from a flight log"). Edit panel can **set** or **add** hours.
- Two tabs: **Service Timeline** and **Log Book**.
- Service Timeline table: drag-reorder (SortableJS via grip handle), title rows vs item rows, a custom Description dropdown (with add/remove custom options), and a Calendar/Clock picker for Last Done / Due Date. "Time Left" is computed client-side.
- Log Book table: existing rows are **readonly**; an add-row appends a flight.

## `dashboard.js` patterns (~1400 lines, one file)
- **AJAX:** `axios`, with the Spring CSRF token read from `<meta name="_csrf">` / `_csrf_header` and sent on every mutating call.
- **Reorder:** SortableJS → `POST /updateOrder`.
- **Time Left:** `calculateTimeLeft(dueDate, currentTach)` parses a due value that may hold a calendar date and/or an hours number (whole or decimal — e.g. `100`, `100.5`, `.5`); recomputed on hours change and at midnight. The Clock-input sanitizer accepts digits plus a single decimal point.
- **Custom dropdowns + date/clock pickers** are hand-rolled (no library); open/close handled by a document click listener.
- **Notifications:** `showToast()` / `showConfirm()` replaced native `alert()`/`confirm()`.
- **Hours "last updated":** `formatUpdated(iso, source)` + `relativeTime()` render the My Hours freshness lines in the browser's local timezone (server stores UTC). `renderUpdatedFromData()` on load (reads `data-updated`/`data-source` attrs); `markUpdatedNow()` after a live change. Uses `textContent`, not `innerHTML`.
- **Excel export:** `exportToExcel()` (SheetJS) is implemented but its button is commented out in `dashboard.html` (intentionally hidden).
- **Print:** `printDashboard()` fills `.print-only` spans then `window.print()`.

## Endpoints the frontend calls
`/updateUserInfo`, `/dashboard` (add), `/update/{id}`, `/delete/{id}`, `/updateOrder`, `/deleteOption/{id}`, `/updateHours`, `/addflightlog`, `/deleteflightlog/{id}`. (See [BACKEND](BACKEND.md).)

## Gotchas
- `dashboard.js` is a large single file; there are duplicate function defs (`closeTypeDropdowns`, `selectOption`).
- Log Book rows are readonly — to fix a typo a user must delete + re-add (a known deferred improvement).
- Login/register field + button labels are injected via CSS `::before` pseudo-elements, which is fragile and not screen-reader friendly.
- No frontend tests exist.
