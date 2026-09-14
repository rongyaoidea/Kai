---
name: finesse-ui
description: Build premium interfaces routed by register: brand/landing spectacle, product dashboards and admin consoles, commerce pages (PDP/listing/cart/checkout), and phone-first h5 pages. Use for landing page, dashboard, admin panel, 后台, 工作台, settings, analytics, checkout, or make-it-premium requests.
---

Route first, then design. A dashboard is a different design language from a landing page — never a landing page with charts bolted on. State the register in one line, then build.

## Registers

- **Brand** (landing, launch, portfolio, hero) — one visual engine (WebGL/Canvas/GSAP-grade motion or a strong typographic idea), an opinionated personality, first-impression craft. One spectacle per page; everything else quiet.
- **Product** (dashboards, admin, tables, analytics, consoles, wizards) — density over decoration: KPI tiles, readable tables with sticky headers, charts with labelled axes, and real states for loading, empty, error, and success. Numerals tabular; destructive actions confirm.
- **Commerce** (product page, listing, cart, checkout) — trust signals, honest pricing, totals that add up, no dark patterns (no forced continuity, no hidden fees, no confirm-shaming).
- **H5** (phone-only pages) — 16px minimum input text, thumb-reach primary actions, viewport-tested layout. Assume the reviewer opens it on a phone.

## Preflight (every register)

1. Cheapness scan: no template rhythm, no stock smile, no unreadable contrast.
2. Accessibility: visible focus, contrast, `prefers-reduced-motion` fallback for all motion.
3. Phone floor: no horizontal scroll at 360px width, tap targets ≥ 44px.

## Kai delivery

- One self-contained HTML file, inline CSS/JS, system fonts or bundled `@font-face` only (same rule as `open_file`: siblings and CDN links won't load at view time).
- Verify by opening it yourself: `open_file`, then `ui_screenshot` where the UI is reachable, and fix what looks cheap before announcing.
- Existing codebase: extract the live tokens/structure from real code first (`document` before `redesign`); never restyle around the established system silently.

## Rules

- Product registers skip hero engines and brand grain — clarity is the aesthetic.
- Wizards and consoles must be finishable: numbered sections, live preview where feasible, pre-submit check, draft-safe.
