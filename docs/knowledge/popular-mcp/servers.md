---
type: Catalog
title: Popular MCP servers
description: Curated free Streamable HTTP MCP endpoints for Kai’s one-tap add sheet.
tags: [mcp, popular-servers]
status: stable
stale_after: 2026-09-28
generated: { by: process:update-popular-mcp-servers, at: 2026-09-15T05:30:00Z }
verified: { by: process:desktopTest-PopularMcpServers, at: PENDING-CI }
sources:
  - id: selection-policy
    resource: /selection-policy.md
    title: Selection and probe policy
  - id: popular-mcp-playbook
    resource: /refresh-playbook.md
    title: Refresh popular MCP servers playbook
---

# Policy

Include a host when **all** of the following hold (see [selection-policy.md](selection-policy.md)):

1. HTTPS Streamable HTTP MCP (`initialize` + `tools/list` like the app client).
2. Free to add (optional free API key allowed).
3. A real end-user job, not a demo echo server.

A probe is **attested live** only when that initialize + tools/list handshake succeeds. Auth-optional hosts (Jina AI) need initialize without a key.

Replace this snapshot only via the [refresh playbook](refresh-playbook.md).

# Current set

Snapshot mirrored into `popularMcpServers` (runtime). 20 hosts: 19 international, 1 China (`market` field; display partition only — probe rules are identical).

| Name | URL | Auth | Market | Probe | Tools |
|---|---|---|---|---|---|
| Context7 | `https://mcp.context7.com/mcp` | none | intl | attested live | 2 — `resolve-library-id`, `query-docs` |
| MDN | `https://mcp.mdn.mozilla.net` | none | intl | attested live | 3 — `get-compat`, `get-doc`, `search` |
| DeepWiki | `https://mcp.deepwiki.com/mcp` | none | intl | attested live | 3 — `ask_question`, `read_wiki_contents`, `read_wiki_structure` |
| Parallel Search | `https://search.parallel.ai/mcp` | none | intl | attested live | 2 — `web_search`, `web_fetch` |
| Yahoo Finance | `https://gateway.mcpservers.org/yahoo-finance/mcp` | none | intl | attested live | 4 — `get_quote`, `search`, `get_chart`, `quote_summary` |
| CoinGecko | `https://mcp.api.coingecko.com/mcp` | none | intl | attested live | 2 — `execute`, `search_docs` |
| Jina AI | `https://mcp.jina.ai/v1` | optional key | intl | attested live (no key) | 22 — read/search/screenshot family |
| Open-Meteo Weather | `https://mcp.open-mcp.org/api/server/open-weather@latest/mcp` | none | intl | attested live | 2 — `expandSchema`, `getweatherdata` |
| Kiwi.com | `https://mcp.kiwi.com` | none | intl | attested live | 2 — `search-flight`, `feedback-to-devs` |
| Malwarebytes | `https://scamguard.malwarebytes.com/claude/mcp` | none | intl | attested live | 6 — `reputation-check_link`, `reputation-check_phone`, `reputation-check_email`, `reputation-report`, `reputation-whois`, `reputation-scan_all` |
| tldraw | `https://tldraw-mcp-app.tldraw.workers.dev/mcp` | none | intl | attested live | 6 — `search`, `exec`, `_exec_callback`, `_get_canvas_state`, `read_checkpoint`, `save_checkpoint` |
| Find-A-Domain | `https://api.findadomain.dev/mcp` | none | intl | attested live | 2 — `check_domain`, `list_tlds` |
| SubwayInfo NYC | `https://subwayinfo.nyc/mcp` | none | intl | attested live | 25 — MTA / bus / ferry / bike / rail arrivals and alerts |
| OctoTrip Rental Cars | `https://mcp.octotrip.app/rental-cars/mcp` | none | intl | attested live | 1 — `search` |
| Oblique Observer | `https://remote.observer/mcp` | none | intl | attested live | 5 — `bazaar_pulse`, `find_provider`, `market_stats`, `experiment_scoreboard`, `crawler_watch` |
| Hugging Face | `https://huggingface.co/mcp` | none | intl | attested live | 4 — `hf_whoami`, `hub_repo_search`, `hub_repo_details`, `hf_fs` |
| Frankfurter FX | `https://mcp.frankfurter.dev/` | none | intl | attested live | 4 — `get_rates`, `convert`, `list_currencies`, `list_providers` |
| AISENSE | `https://aisenseapi.com/mcp` | none | intl | attested live | 28 — time/uuid/links/storage/webhooks/approvals/wake/heartbeat/lease/inbox/queue |
| Steam Trends | `https://steam.api.trendsapi.ai/mcp` | none | intl | attested live | 3 — `trends___get_growth`, `trends___get_time_series`, `trends___get_top_trends` |
| Caiyun Weather | `https://mcp-weather.caiyunapp.com/mcp` | optional key (`X-Caiyun-API-Key`) | CN | attested live (no key) | 5 — realtime/hourly/weekly/history/alerts |

# Snapshot

| Field | Value |
|---|---|
| Probed | 2026-09-15T05:30:00Z |
| Handshake | `initialize` (2026-07-28 → 2025-11-25 → … → 2024-11-05 fallback, like McpClient) + `notifications/initialized` + `tools/list`, browser User-Agent |
| Current | 20 attested live (18 kept + Steam Trends + Caiyun Weather) |
| Dropped this refresh | none |

# Notes

- Jina AI still uses `requiresAuth: true` in Kotlin so the add sheet shows a key field. The probe listed 22 tools without a key; search tools still need a free jina.ai key.
- `remote.mcpservers.org` Fetch and Sequential Thinking stay out (removed earlier).
- Probe hardening: five hosts (Parallel, Yahoo Finance, CoinGecko, tldraw, SubwayInfo) 403 a `Python-urllib` UA at the edge but pass with a browser UA — future probes must send one, and a 403 is not proof of auth-gating.
- Considered but excluded: Mapbox (`mcp.mapbox.com/mcp` — 401 on initialize, token required), Pipeworx npm gateway (`gateway.pipeworx.io/npm/mcp` — live but 41 tools incl. betting helpers, too bloated for one-tap), Verifyum/aamio (same operator as AISENSE; on-chain writes / stranger content — wrong risk profile for one-tap), SEC EDGAR hosts (key-gated), HN/Reddit/Wikipedia/arXiv/Open Library (no hosted Streamable HTTP endpoint found — stdio/self-host only), OctoTrip Flights (duplicate of Kiwi.com).
- Do not add replacement hosts without an explicit product decision.
