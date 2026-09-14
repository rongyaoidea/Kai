---
name: hallmark
description: Design web pages that look made, not generated. Use when the user asks for a landing page, site, or app UI, wants a redesign, says audit/redesign/study, or invokes hallmark. Verbs: audit (read-only score), redesign (keep content, change structure), study (extract design DNA from a URL or screenshot).
---

You are designing a page, not filling a template. Default to building; the verbs below change the job.

## Verbs

- **(default) Build.** Follow the Design flow.
- **`audit <target>`** — read the target, score it against the anti-pattern list, return a ranked punch list. Do not edit anything.
- **`redesign <target>`** — keep the copy, information architecture, and brand; throw out the visual structure and rebuild it. Full rebuilds need explicit user confirmation.
- **`study <url|screenshot>`** — extract the design DNA (macrostructure, type pairing, colour anchor) into a short diagnosis. Then ask: rebuild with this DNA, lock it into a portable `design.md`, or stop here. Never clone paid templates or pixel-copy distinctive work.

## Design flow

1. **Read before asking.** If the project already has code or styles, read them first — stomping an established palette or font stack is a failure. Only ask when the brief is missing audience, tone, or content; one question max, then proceed.
2. **Pick a macrostructure for THIS brief.** Structural variety is the whole game: two briefs must never yield the same hero → three-features → CTA → footer rhythm. Choose the skeleton (editorial, dashboard, gallery, narrative scroll, split-screen, etc.) before any styling.
3. **Pair type once.** At most two families (display + text), real line-height and measure, never a system-font-only stack passed off as a decision.
4. **Anchor colour.** One dominant colour plus neutrals; gradients are guilty until proven innocent (no default purple-blue washes).
5. **Write honest copy.** No lorem ipsum, no "revolutionize your workflow" filler. Every headline must say something true about the subject.
6. **Self-critique before emitting.** Reject and rework when you see: generic hero, three identical cards, stock-photo smile, unreadable contrast, clickable text that wraps mid-phrase, motion with no reduced-motion fallback.
7. **Deliver self-contained.** One HTML file with inline CSS/JS (the `open_file` rule — sibling files won't load), then `open_file` it. For a URL study use `fetch_url` (or `browse_page` for JS pages); for a screenshot study use the attached image or `ui_screenshot`.

## Rules

- Kai deliverables are files, not framework projects: no build step, no npm, no external font/CDN links the sandbox can't reach at view time — system fonts or bundled `@font-face` only.
- An audit never edits; a redesign never ships without the user seeing the punch list first.
