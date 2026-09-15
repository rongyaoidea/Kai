# Popular MCP knowledge update log

## 2026-09-15

* **Update**: MCP market with China/International tabs (new `market` field on `PopularMcpServer`; Chinese-locale devices land on the China tab).
  * **Added (2)** — Steam Trends (no key, 3 tools, international), Caiyun Weather (free key via `X-Caiyun-API-Key`, 5 tools, China; second `requiresAuth` host after Jina, first with a non-Bearer scheme).
  * **CN research outcome** — Amap/Baidu Maps/Bocha/QWeather/Bilibili/NetEase/Eastmoney are stdio, per-user expiring URLs, or key-gated commercial with no static endpoint; none can one-tap. OctoTrip Flights skipped as a Kiwi.com duplicate.
  * 18 → 20 hosts.
* **Pending**: set `verified` in servers.md once desktopTest-PopularMcpServers goes green on CI.

## 2026-09-14

* **Update**: Web research + live probe (initialize with 2026-07-28 → 2024-11-05 fallback like McpClient + `tools/list`, browser UA, retry-once on timeout/5xx).
  * **Re-attested live (13/13 kept)** — Context7, MDN, DeepWiki, Parallel Search, Yahoo Finance, CoinGecko, Jina AI (no key), Open-Meteo Weather, Kiwi.com, Malwarebytes, tldraw, Find-A-Domain, SubwayInfo NYC.
  * **Added (5)** — OctoTrip Rental Cars, Oblique Observer (free tier only; paid `oblique.markets` catalog stays out), Hugging Face, Frankfurter FX (official), AISENSE (28 tools; heavy but all no-key utilities).
  * **Probe lesson** — `Python-urllib` UA gets edge-403s on five hosts; browser UA passes. A 403 alone is not auth-gating.
  * 13 → 18 hosts. Runtime list, README table, and this bundle updated together.
* **Pending**: set `verified` in servers.md once desktopTest-PopularMcpServers goes green on CI.

## 2026-08-12

* **Update**: Live probe via `process:update-popular-mcp-servers` (initialize protocol `2024-11-05` + `tools/list`).
  * **Attested live** — Context7, MDN, DeepWiki, Parallel Search, Yahoo Finance, CoinGecko, Jina AI (no key; 22 tools listed), Open-Meteo Weather, Kiwi.com, Malwarebytes, tldraw, Find-A-Domain, SubwayInfo NYC.
  * **Dropped** — Manifold Markets (`https://api.manifold.markets/v0/mcp`): Cloudflare 526 invalid origin TLS, `retryable: false` / `owner_action_required`. 14 → 13 hosts.
  * **Policy** — non-retryable origin 526/530 is a must-drop, distinct from a flaky timeout/5xx.
* **Initialization**: Created OKF popular-mcp bundle (selection policy + probe playbook). Runtime source of truth remains `PopularMcpServers.kt`.
