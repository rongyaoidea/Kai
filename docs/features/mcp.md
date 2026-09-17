# MCP Servers

**Last verified:** 2026-09-17

Kai supports external tool servers via the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP). Users can connect to remote MCP servers using Streamable HTTP transport and use their tools alongside native tools.

## Concepts

### MCP Server

A remote service that exposes tools via the MCP JSON-RPC protocol. Each server has a name, URL, optional authentication headers, and an enabled state. Server configurations are persisted as JSON in app settings.

### MCP Tool

A tool discovered from a connected MCP server. Wraps the server's tool definition as a native `Tool` implementation so it integrates seamlessly with the existing tool executor and AI request pipeline. Each MCP tool has an ID of `mcp_{serverId}_{toolName}` and can be individually toggled; newly discovered tools default to enabled, so the agent can call them on the next turn without a settings round-trip.

### Popular Servers

A curated list of verified free MCP endpoints. Displayed as quick-add shortcuts in the add server bottom sheet, split into **International** and **China** market tabs (Chinese-locale devices land on the China tab). Selection criteria: free, Streamable HTTP transport, practically useful, reasonably stable, static public URL. Most require no API key and one-tap add. Auth-optional servers (Jina AI, Caiyun Weather) prefill the form with an optional API key field under each host's own header (`Authorization: Bearer …` vs `X-Caiyun-API-Key`); the server can still be added without a key, but key-gated tools need a free key afterwards. Existing user-defined headers are never overwritten. The current names live in the runtime popular-server list in code; selection policy, last probe results, and a mirrored snapshot live in the knowledge bundle under `docs/knowledge/popular-mcp/`. Both are refreshed together with the `update-popular-mcp-servers` skill. Dead hosts (`remote.mcpservers.org` Fetch and Sequential Thinking) were removed earlier.

Below the one-tap list, a Browse Marketplaces section links out to external MCP directories — Smithery, mcp.so, Glama and the official registry on the International tab; the ModelScope plaza, the Alibaba Bailian plaza and the Amap MCP docs on the China tab — following the same market tabs. Marketplace entries open in the system browser; users pick a server there and paste its Streamable HTTP URL into the add-server form above. Only remote servers can be added; stdio-only entries do not work, and expiring per-user URLs (such as temporary hosted domains) should not be expected to stay connected.

## Adding a Server

In the Tools tab of settings, the "MCP Servers" section appears above native tools. Users can:

- Tap "Add MCP Server" to open a bottom sheet
- Enter a name, URL, and any number of custom headers manually (e.g., `Authorization`, plus additional vendor-specific headers); rows can be added or removed individually
- Or pick from the popular servers list: no-auth servers one-tap add; auth-optional servers (Jina AI) prefill name/URL and show an optional API key field (Add works without a key)

MCP server configurations are included in the settings export/import feature, so the full set of servers (and their headers) can be moved between devices.

The **agent can add and remove servers itself** through `add_mcp_server` / `list_mcp_servers` / `remove_mcp_server` (Tools tab switches, default on). `add_mcp_server` registers the server, connects, and returns the tools it exposes in one step; duplicate URLs are detected ignoring trailing slashes, host case, default ports and fragments (path case and query count), and headers are accepted as an object or a JSON string. A connection failure leaves the server configured so the settings Refresh action can retry — the failure message also tells the agent to remove-then-add again, since the agent has no refresh action of its own. Because both paths write the same store, anything the agent installs shows up in Settings → Tools → MCP Servers immediately. When two servers expose the same tool name, the first one connected answers it deterministically; each server's full tool list stays visible on its own card.

## Connection Flow

When a server is added or enabled:

1. Kai creates an `McpClient` for the server URL and headers
2. It negotiates the protocol version newest-first (`2026-07-28`, then `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`, `2024-10-07`): for each version it tries the stateless flow first — a bare `tools/list` with the protocol-version and method headers, no handshake
3. If the stateless probe fails with a non-version error, Kai falls back to the legacy handshake on the same version: an `initialize` JSON-RPC request with client capabilities (declaring the Tasks extension), then a `notifications/initialized` notification, then `tools/list`. A version rejection (`Unsupported protocol version`, as Firecrawl returns for `2026-07-28`) skips to the next older version instead. The `initialize` reply's own `protocolVersion` is adopted when it names a version Kai speaks, and every later request uses the negotiated version
4. Discovered tools are registered with their metadata (name, description, input schema, plus any `_meta.ui.resourceUri` app template)
5. The server appears as connected (green dot) in settings

When the chat screen first opens, all enabled MCP servers are reconnected in the background in parallel. The first time the settings screen becomes visible in a session, the same connect sweep runs once (alongside connection validation for services); later returns to settings do not automatically re-connect failed servers. Servers also reconnect when the user enables a server, taps refresh, or imports MCP settings. Connection state is protected by a mutex to prevent data races from concurrent connections, and individual server failures do not block other servers from connecting.

## Server Management

Each server card in settings shows:

- A status dot (green=connected, orange=connecting, red=error, grey=unknown), an enable/disable toggle, and a dropdown chevron
- Clicking anywhere on the card expands/collapses it
- When expanded: discovered tools with individual toggles, refresh button, remove button (removal is deferred with a snackbar "Undo" option before permanent deletion)
- Disabling a server disconnects it immediately and the status dot reflects the change

The UI uses the same card style, status dot colors, and spacing as the Services tab for visual consistency.

## Transport

Kai speaks Streamable HTTP, preferring protocol `2026-07-28` with automatic fallback to `2025-11-25` … `2024-10-07`:

- POST requests with `Content-Type: application/json` and `Accept: application/json, text/event-stream`, plus the `MCP-Protocol-Version` and `Mcp-Method` routing headers
- Both direct JSON responses and SSE (Server-Sent Events) responses are handled
- **Version negotiation first**: each version is tried stateless-first, then legacy-handshake. Servers that reject the newest version with `Unsupported protocol version` (e.g. Firecrawl, which caps at `2025-11-25`) are retried with the next older version; either way the user just sees connected. Non-JSON HTTP 400 bodies are surfaced as errors so the negotiation can react to them.
- No stdio transport support

## Long-running tools (Tasks extension)

Servers can answer `tools/call` with a durable task handle instead of a final result (`resultType: "task"`). Kai advertises the `io.modelcontextprotocol/tasks` extension on every call and, when a handle comes back, polls `tasks/get` at the server's suggested interval until the task completes, fails, or is cancelled — the agent sees one synchronous answer either way, so tools that run longer than the 60-second HTTP window now work instead of timing out. A task that pauses for input (`input_required`, e.g. an approval elicitation) is cancelled cooperatively and the request is returned to the agent as an error describing what was asked, so it can relay the question to the user rather than stalling.

## Interactive UI (MCP Apps)

Tools that ship an interactive UI declare it in their description metadata (`_meta.ui.resourceUri`, a `ui://` resource). Kai discovers these URIs at connect time and can fetch the HTML template via `resources/read`. Any tool row carrying a template shows an **Open UI** button that renders it in-app — no per-tool UI code needed, so newly installed servers extend the frontend automatically.

Rendering is Android-only (system WebView host): JavaScript on, file/content access off, navigation locked inside the bundled document. The page talks to Kai through a minimal `KaiAppBridge` subset — `window.KaiAppBridge.postMessage(string)` with `{id, method, params}` for `ui/initialize` and `tools/call` (proxied to the tool's own server), replies arriving at `window.__kaiOnMessage(string)`. Data-driven apps that call tools and render results work; the full postMessage-iframe dialect (push channels, capability-gated permissions) is future work, and unsupported methods get an explicit error reply instead of silence. The agent uses the underlying tools through the normal path either way, so nothing is blocked on the UI.

## Authentication

Custom headers (e.g., `Authorization: Bearer <token>`) can be configured per server and are sent with every request. Auth-optional popular servers (Jina AI) collect an optional API key in the add sheet and, when provided, store it as an Authorization header (`Bearer` is prefixed automatically when missing). The server can be added with no key.

## Integration with Tools

MCP tools are automatically available to the AI — no changes needed to the tool executor or request serialization. The platform layer's `getAvailableTools()` includes enabled MCP tools from the `McpServerManager`. MCP tools have a 60-second timeout (vs 30s default for native tools). MCP tools are only shown within their server's expanded card in settings, not in the native tools list.

Tool calls to MCP servers go through the same execution pipeline as native tools: the tool executor finds the tool by name, the `McpTool` wrapper sends a `tools/call` JSON-RPC request to the server, and the result is returned to the AI.

## Limitations

- HTTP/SSE transport only (no stdio)
- CORS may block MCP server requests on the web platform
- MCP tool parameters preserve full JSON Schema (including nested `items`, `properties`, `enum`) for accurate API serialization

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../mcp/McpClient.kt` | MCP JSON-RPC client over HTTP/SSE: stateless-first connect, Tasks polling, `resources/read` |
| `composeApp/src/commonMain/.../mcp/McpProtocol.kt` | Pure protocol helpers (headers, Tasks capability, poll timing, input summaries) |
| `composeApp/src/commonMain/.../mcp/McpServerManager.kt` | Server lifecycle, connection, tool discovery |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../mcp/McpTool.kt` | Wraps MCP tools as native Tool implementations |
| `composeApp/src/commonMain/.../mcp/McpServerConfig.kt` | Server configuration data model |
| `composeApp/src/commonMain/.../mcp/McpModels.kt` | JSON-RPC DTOs and MCP-specific models |
| `composeApp/src/commonMain/.../mcp/PopularMcpServers.kt` | Curated list of verified MCP endpoints plus marketplace directory links |
| `docs/knowledge/popular-mcp/` | OKF bundle: selection policy, last probe snapshot, refresh playbook |
| `composeApp/src/commonMain/.../ui/settings/McpSection.kt` | MCP server card UI, add-server bottom sheet with multi-header editor, popular list and marketplace links |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Hosts the MCP section inside the Tools tab content |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | MCP connection management and UI state |
| `composeApp/src/commonMain/.../ui/settings/SettingsUiState.kt` | McpServerUiState, McpConnectionStatus, McpAppDialogState |
| `composeApp/src/commonMain/.../ui/mcp/McpAppDialog.kt` | Cross-platform app-host contract (Android WebView actual; no-op elsewhere) |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | MCP server config persistence |
