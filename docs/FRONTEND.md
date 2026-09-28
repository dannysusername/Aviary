# Frontend Map

> Covers `src/main/resources/templates/**` and `src/main/resources/static/**`. Update this file when you change a template, stylesheet, or JS behavior.

## Pages (Thymeleaf)
- `templates/login.html` + `static/css/loginstyle.css` + `static/js/signin.js` (password eye toggle).
- `templates/register.html` + `static/css/registerstyle.css`.
- `templates/dashboard.html` + `static/css/dashboardstyle.css` + `static/js/dashboard.js` — the whole app.

## Theme
Each stylesheet defines the same CSS-variable palette (`--primary` green `#2C9B6F`, `--accent` pink `#E64E6D`, surfaces, radius, shadows). Font: Inter via Google Fonts.

## Dashboard structure
- Fixed header (logo, **Share** menu, logout, settings gear). Share is a popover card (`.share-dropdown`, toggled via `hidden` + `aria-expanded` by `setShareOpen()` in `dashboard.js`): Print, Download PDF, then a divider and Email/Text marked with a "Soon" pill (`.share-coming-soon`). Closes on outside click, Escape, or picking an item; Arrow keys move between items. Styled with theme tokens so it works in dark mode.
- "My Hours" card: shows Hobbs/Tach, each with a small muted "last updated" line under it (`.hours-block` / `.hours-updated`) — local date + time + relative ("2 days ago") + source ("you edited it" / "from a flight log"). Edit panel can **set** or **add** hours.
- Two tabs: **Service Timeline** and **Log Book**.
- Service Timeline table: drag-reorder (SortableJS via grip handle), title rows vs item rows, a custom Description dropdown (with add/remove custom options), and a Calendar/Clock picker for Last Done / Due Date. "Time Left" is computed client-side.
- Log Book table: existing rows are **readonly**; an add-row appends a flight.

## `dashboard.js` patterns (~1400 lines, one file)
- **Autosave debounce:** one 500ms timer per row (`rowSaveTimers`, keyed by row id) and per aircraft-info field (`userInfoSaveTimers`, keyed by input name). Never share one timer across rows/fields — that used to silently drop the first of two quick edits. A failed save shows an error toast.
- **Escaping:** anything user-typed or from AeroAPI that goes into an `innerHTML` template must pass through `escapeHtml()` (top of `dashboard.js`). Prefer `textContent` when building single nodes.
- **AJAX:** `axios`, with the Spring CSRF token read from `<meta name="_csrf">` / `_csrf_header` and sent on every mutating call.
- **Reorder:** SortableJS → `POST /updateOrder`.
- **Time Left:** `calculateTimeLeft(dueDate, currentTach)` parses a due value that may hold a calendar date and/or an hours number (whole or decimal — e.g. `100`, `100.5`, `.5`); recomputed on hours change and at midnight. The Clock-input sanitizer accepts digits plus a single decimal point.
- **Custom dropdowns + date/clock pickers** are hand-rolled (no library). The chevron toggles a menu; tapping the Description text or an empty Last Done / Due Date cell toggles it too. One persistent document listener closes any open menu on a click outside it (`isDropdownToggle` in `dashboard.js`).
- **Last Done / Due Date menu** buttons read Add / Remove (state is the button text). Removing a field that has a value takes two taps: the first arms it as a red "Remove?" (`.confirm-remove`), the second removes it and auto-saves; reopening the menu disarms it (`disarmRemoveButton`). Empty fields remove on one tap.
- **`[hidden]` always wins** (`[hidden] { display: none !important; }` at the top of `dashboardstyle.css`). Toggle visibility with `el.hidden`; rules like `.settings-table tr { display: flex }` used to override the attribute and show hidden rows.
- **Mobile (≤960px) Settings**: each row stacks the label above a full-width, wrapping value area with 42px controls.
- **Mobile (≤960px) Service Timeline** renders each row as a card (`dashboardstyle.css`, "Service Timeline cards"). Saved cards and the add card share the same rules via `#sortable-info-table :is(.sortable tr:not(.title-row), .add-row)`; spacing/tap size are CSS variables on `#sortable-info-table` (`--cell-px`, `--cell-py`, `--tap`) and are fluid down to ~300px. There are deliberately no breakpoints below 960px.
- **Notifications:** `showToast()` / `showConfirm()` replaced native `alert()`/`confirm()`.
- **Hours "last updated":** `formatUpdated(iso, source)` + `relativeTime()` render the My Hours freshness lines in the browser's local timezone (server stores UTC). `renderUpdatedFromData()` on load (reads `data-updated`/`data-source` attrs); `markUpdatedNow()` after a live change. Uses `textContent`, not `innerHTML`.
- **Excel export:** `exportToExcel()` (SheetJS) is implemented but its button is commented out in `dashboard.html` (intentionally hidden).
- **Print:** `printDashboard()` fills `.print-only` spans then `window.print()`.

## Endpoints the frontend calls
`/updateUserInfo`, `/dashboard` (add), `/update/{id}`, `/delete/{id}`, `/updateOrder`, `/deleteOption/{id}`, `/updateHours`, `/addflightlog`, `/deleteflightlog/{id}`. (See [BACKEND](BACKEND.md).)

## Gotchas
- `dashboard.js` is a large single file; there are duplicate function defs (`selectOption`).
- Log Book rows are readonly — to fix a typo a user must delete + re-add (a known deferred improvement).
- Login/register field + button labels are injected via CSS `::before` pseudo-elements, which is fragile and not screen-reader friendly.
- No frontend tests exist.
