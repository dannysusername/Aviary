# Share / Export Feature Spec

Status: **Designed, not yet built** · Last updated: 2026-06-14

Lets a user print, download, email, or text a PDF of their maintenance data.
Replaces the standalone "Print Dashboard" button and groups all output actions
under one menu. The existing **Upload GARMIN CSV** button stays separate (it is
an *import*; everything here is *export/share*).

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

1. **Phase 1 — server-side PDF generation.** Foundation for download/email/text.
   No external accounts needed. Add PDF lib (openhtmltopdf), build a
   print-friendly template, expose `/pdf?scope=...` to download + eyeball.
2. **Phase 2 — Email + attachment** via SendGrid (needs SendGrid account + API
   key, SPF/DKIM DNS for deliverability).
3. **Phase 3 — Twilio SMS** with tokenized PDF link (needs Twilio number +
   A2P 10DLC registration).

## Cross-cutting concerns

- **Abuse / rate limiting** — self-serve send to arbitrary recipients is a spam
  vector; cap sends per user per hour, validate recipient input.
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
