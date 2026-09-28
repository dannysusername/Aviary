# Share / Export Feature Spec

Status: **Print/Download PDF done 2026-09-06 (server-side rendering, see below); Email/Text still not built** · Last updated: 2026-09-06

Lets a user print, download, email, or text a PDF of their maintenance data.
Groups all output actions under one menu, alongside the separate standalone
**Print Dashboard** button (which the Share menu's Print item reuses directly
rather than replacing). The existing **Upload GARMIN CSV** button stays
separate (it is an *import*; everything here is *export/share*).

## Current state (2026-09-06)

- **Print** calls the browser's native `window.print()` (same as the
  existing `#print-dashboard` button), using the `.no-print`/`.print-only`
  CSS that already builds the print-ready document.
- **Download PDF** generates a real PDF **server-side**, via
  `PdfExportService` + [openhtmltopdf](https://github.com/danfickle/openhtmltopdf)
  (`GET /pdf`, `templates/pdf-export.html`). Real vector text and tables —
  small file (~7KB for a typical dashboard vs. ~550KB for the earlier
  approach below), selectable/searchable text, smooth scrolling in any PDF
  viewer, one click starts the download immediately (no dialog).

  **First attempt was client-side** (`html2pdf.js` / html2canvas+jsPDF):
  screenshotted the print-ready DOM and saved the image as a PDF. Worked,
  but every "page" was one full-resolution raster image — the user reported
  it scrolled laggy in PDF viewers, and inspecting the file confirmed why
  (`pdfinfo`/`pdfimages`: 2 pages, each a single ~2000×1000px JPEG, no real
  text). Replaced with the server-side approach above.

  **openhtmltopdf constraint worth knowing:** it parses its input as strict
  XML, not lenient HTML5 — `pdf-export.html` is hand-written as well-formed
  XHTML for this reason (self-closed tags, and critically, no `--` inside
  HTML comments, which is invalid XML and throws
  `SAXParseException: The string "--" is not permitted within comments` —
  hit this twice while building it). It also only supports CSS 2.1 plus
  some CSS3 — no flexbox/grid, no CSS custom properties (`var()`) — so
  `pdf-export.html` is a standalone template with its own plain
  tables/blocks and literal colors, not a shared fragment with
  `dashboard.html`.

There's no shared dialog, no scope selector, no recipient/message fields —
those only make sense once Email/Text exist, since Print/Download need no
configuration at all.

Email and Text are visible in the menu but disabled (`.share-coming-soon`,
shows a "Coming soon" toast) until SendGrid/Twilio are actually set up. The
full design below (shared dialog, scope selector, per-channel recipient
fields) is still the plan **for when Email/Text get built** — Print/Download
intentionally bypass all of it.

## UX flow

```
 Share ▾  (⋮ on mobile)
 ┌──────────────────────────┐
 │  Print                   │
 │  Download PDF            │  ──▶ all four open the SAME shared dialog
 │  Email…                  │
 │  Text…                   │
 └──────────────────────────┘
                │
                ▼
 ┌──────────── Share ──────────────┐
 │  Include:                        │
 │    ◉ This tab        ← default   │
 │    ○ Full dashboard              │
 │  ─────────────────────────────   │
 │  Recipient: [____________]       │  ← ONLY for Email / Text
 │  Message:   [____________]       │  ← optional, Email / Text only
 │                                  │
 │            [Cancel]  [Confirm]   │
 └──────────────────────────────────┘
```

- Every menu item routes into one shared dialog.
- **Scope selector always on top**, with **"This tab" pre-selected** so the
  default path is just open → Confirm.
- Recipient + optional message fields appear **only** for Email / Text.
- `…` on Email/Text signals "opens a dialog for more input."

## Scope

Scope applies to **all four actions, including Print**:

- **This tab** (default) — only the active tab (Service Timeline *or* Log Book).
- **Full dashboard** — both tabs' content in one document. Slightly more work on
  both render paths (must reveal/render both sections).

## PDF content = data only, no UI chrome

The PDF contains only the record content: **Service Timeline, Aircraft Info,
Hours (Hobbs/Tach), and Log Book**. All interactive controls are excluded —
the Share menu, grip handles, trash/delete icons, complete-maintenance button,
chevrons, dropdowns, etc.

This maps directly onto the existing **`.no-print` / `.print-only`** class
system: PDF content ≈ "everything that is *not* `.no-print`", with `.print-only`
spans supplying clean text values. The server PDF template can reuse that split.

Font Awesome note: the FA **Kit** (`kit.fontawesome.com/647fe92b87.js`) is
*browser-runtime JS* — it does not execute during server-side PDF generation.
This is a non-issue because all icons live on excluded UI chrome. Only if a
future PDF needs an icon in *content* would we have to embed the FA font/SVG.

## Two rendering engines (intentional)

| Action                  | Engine                                   | Why |
|-------------------------|------------------------------------------|-----|
| Print                   | Browser `window.print()` + `@media print` CSS | It's the live page |
| Download / Email / Text | Server-side PDF (openhtmltopdf)          | Email needs a real attachment; SMS needs a hosted file |

Both obey the scope choice, but differently:
- **Print** — scope controls which tabs are *visible* before `window.print()`.
- **Server PDF** — scope is a parameter to the endpoint (e.g. `/pdf?scope=...`).

Known trade-off: the printed page and the emailed PDF may look slightly
different (two stylesheets / two render paths). Acceptable to start; converging
them to pixel-identical is a later investment.

## Trigger placement

- Desktop (≥961px): `Share ▾` labeled button in the header `.buttons` block,
  replacing the current Print button.
- Mobile (≤960px): collapses to a compact kebab `⋮` (hide the label via the
  existing `@media (max-width: 960px)` block).
- Garmin upload stays in its own `.upload-csv` block (import ≠ export).

## Channels

- **Email** — server-side send via **SendGrid** (Twilio-owned; one vendor with
  SMS). PDF attached to a `MimeMessage`.
- **Text** — **Twilio** SMS. SMS can't attach files, so: store the generated PDF,
  mint a **tokenized, expiring download link**, text the link.

## Build order

**Print, Download PDF, and server-side PDF generation are all done**
(2026-09-06) — Print triggers `window.print()`; Download PDF and the
`PdfExportService`/`GET /pdf` it's built on are covered in "Current state"
above. Phase 1 below is therefore already satisfied by that same service —
Email/Text can call `PdfExportService.generateDashboardPdf(user)` directly
for their attachment/link instead of building their own renderer.

1. ~~Phase 1 — server-side PDF generation.~~ **Done** — see `PdfExportService`
   above. Originally scoped as Email/Text-only groundwork; turned out to be
   needed sooner, for Download PDF itself, once the client-side approach
   proved too slow to scroll.
2. **Phase 2 — Email + attachment** via SendGrid (needs SendGrid account + API
   key, SPF/DKIM DNS for deliverability).
3. **Phase 3 — Twilio SMS** with tokenized PDF link (needs Twilio number +
   A2P 10DLC registration).

## Cross-cutting concerns

- **Abuse / rate limiting** — self-serve send to arbitrary recipients is a spam
  vector. Done: `POST /pdf/email` validates the address and counts against
  `OutboundEmailLimiter` (10 user-triggered emails per user per hour, shared
  with alert invites/resends) → 429 past the cap.
- **Tokenized PDF links** (for SMS) must be unguessable + expiring so records
  can't be enumerated.
- **Empty timeline** — disable Share or guard against emailing a blank PDF.
- **States** — "Generating…" loading state; reuse existing `showToast` for
  success/failure.
- **Excel export** — currently commented out; omit from the menu until real.

## External setup the user must do (cannot be coded)

| Item | For |
|------|-----|
| SendGrid account + API key | email |
| Twilio account + phone number (~$1–2/mo) | SMS |
| A2P 10DLC registration | texting US numbers |
| SPF/DKIM DNS records | email deliverability |

Secrets go in env vars / `application-*.properties` — never committed.
