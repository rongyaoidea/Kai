# Cross-App Automation

**Last verified:** 2026-09-12

Kai can see and control other Android apps on the user's behalf: read what's on screen, tap buttons, fill forms, scroll lists, launch apps, and — once the Shizuku service is running — send raw key events and run ADB-level shell commands. Available on **Android only**; every other platform hides the feature completely.

## How It Works

Perception is text-first. The agent reads the foreground app as a structured element list (visible text, content descriptions, tappable and editable flags, screen coordinates) and acts on short-lived element ids. Screenshots exist for the user, not the agent — coordinates from the element tree are exact, pixels are not.

The same seven tools run over two interchangeable backends, picked automatically per call — the accessibility service while it is bound, otherwise Shizuku. The settings section shows which channel is active.

Control flows through two privilege layers:

- **UI layer** — the system accessibility service exposes the element tree and performs taps, text input, scrolls, and system keys (back/home/recents). No root, no computer, no developer options needed; the user enables Kai once under system Accessibility settings.
- **Shizuku layer** — serves the *same* UI tools with no accessibility setup at all: the element tree comes from the platform `uiautomator` tool (which drives the built-in UiAutomation framework as the shell user), and taps, typing, swipes, and key events go through raw `input` injection — the same channel a finger uses, so WebViews and other accessibility-flaky surfaces such as Chrome search boxes behave more stably. The same layer additionally exposes hardware key events (including Enter and D-pad keys the UI layer cannot send), package and system-setting queries, and raw shell commands outside the Linux sandbox. Privileged commands run in a Shizuku **user service**: a `Binder` subclass Shizuku instantiates inside its own process (shell uid, or root when Shizuku runs rooted). It is keyed by a stable tag (`kai-privshell`), so app updates reuse the same process instead of orphaning it, and it implements Shizuku's reserved destroy transaction so the privileged process exits when the service is replaced or removed.

A note on naming: Android's `UiAutomation` class itself is hidden from third-party apps, so Kai does not instantiate it directly — it drives the platform's own `uiautomator` bridge, which is that same framework running with shell privileges. Same engine, no hidden APIs.

## Safety Model

Everything is off by default behind three independent switches in Settings → Agent → Automation: the master switch, the control-actions switch, and the Shizuku switch. Each automation tool additionally keeps its own switch in the Tools tab, so turning a capability on is always a deliberate two-step act.

Two fences apply to every write, no matter which tool performs it:

- **Sensitive blocklist** — banking and payment apps, password managers, authenticators, and the system package installer can never be driven. The agent is told to refuse with an explanation instead of retrying.
- **User allowlist** — an optional list of package names. When set, control is limited to exactly those apps; when empty, every app except the blocklist is fair game. Reads are never restricted — an agent that cannot see cannot act safely.

Tools are only offered while their backing service is actually alive: the tool list is rebuilt with the accessibility binding and the Shizuku binder state taken into account, and every execution re-checks, so a grant revoked mid-chat fails with a repair hint instead of acting. Irreversible actions (payments, deletions, sending content out) must be confirmed with the user first — the agent's instructions say so, and the blocklist makes the most dangerous targets unreachable in the first place.

On-device (LiteRT) runs never receive automation tools: coordinate-and-id operation is too brittle for small local models, which hallucinate actions instead of emitting valid calls.

## Available Tools

| Tool | Tier | What it does |
|------|------|--------------|
| Read App UI | Read | Element list of the foreground app, with tappable ids; optional text pre-filter |
| Screenshot App | Read | System-wide screenshot to a file, shown inline in the chat automatically (for the user; the agent keeps using the tree) |
| Watch UI Events | Read | Foreground package plus recent window/notification events |
| Control App UI | Write | Tap / type / scroll / system key on fresh element ids |
| Launch Apps | Write | Open another app by package, activity, or deep link; `package="self"` returns to Kai |
| Privileged Shell | Shizuku | ADB-level shell outside the sandbox; installs, permission grants, and data wipes require explicit per-command approval |
| Privileged Input | Shizuku | Raw key events, taps, swipes, and text through the system input command (ASCII via `input text`; Unicode/CJK via clipboard + KEYCODE_PASTE; length-capped) |

Element ids stay usable across screen changes: an action first refreshes the cached handle and, when it has detached, re-locates the element by the identity captured at dump time (resource id + text/description + class) in the current tree. Shizuku-backend ids do the same by re-dumping and matching on identity. Ids expire after about five minutes; only when even the identity is gone does the action fail with a "re-run Read App UI" hint rather than acting on a recycled element. Accessibility ids and Shizuku ids live in separate namespaces, so a dump taken on one channel stays valid if the other takes over mid-task.

### Waiting, verification, and popups

- Every write action briefly waits for the accessibility event stream to settle (bounded, ~1.2s) before returning, so the next dump reflects the result instead of a mid-animation frame.
- `ui_act` also supports `wait_for` (wait until text appears, or disappears with `gone`) and an optional `expect_text` on tap/input actions: the action result carries `verified: true/false` so the agent can branch on what actually happened instead of assuming success.
- Before each write action, obvious transient dialogs (onboarding "Skip"/"Not now", update prompts, ...) are dismissed automatically using a conservative exact-label allowlist. Allow/confirm/agree-style buttons are deliberately excluded: dismissing a prompt is safe, accepting one is the user's decision.
- **App cards**: the `app_card` tool stores per-app operating notes (where the search box is, which popups to skip, which flow worked). It is on by default (it only writes local notes; a master-only automation gate still applies). `ui_dump` automatically includes the card for the foreground app, and — while no card exists — an `app_card_hint` prompting the agent to save one after a successful task, so it stops re-discovering the same app every session. Cards are local settings (max 100, 2000 chars each) and listable/removable via the tool.
- **Launching apps**: `app_launch` resolves launcher entries through Android's PackageManager; an Android 11+ MAIN/LAUNCHER `<queries>` entry keeps installed apps visible, and when the launcher entry still isn't resolvable it falls back to Shizuku (`cmd package resolve-activity` + `am start`). A "not installed" result therefore means genuinely no launcher entry — the tool description tells the agent to verify with `pm list packages` instead of silently downgrading to a web search.
- **Returning to Kai**: `app_launch` accepts `package="self"` (or `"kai"`) to bring Kai back to the front, and the agent is told to use it as the final step of a cross-app task. In addition, any user turn that used `ui_act` / `app_launch` / `privileged_input` automatically brings Kai forward when it finishes (success or failure), so the user reads the summary in Kai rather than being left in the foreign app. The return prefers Shizuku's `am start` (exempt from Android's background-activity-launch limits) and falls back to a direct activity start.

## Setup

1. Settings → Agent → Automation → enable the master switch.
2. Each channel now has its own authorization status card (status dot plus the action its state needs), the same pattern as the MCP server cards:
   - **Accessibility service** — green when Kai is bound, red otherwise with a button that opens the system Accessibility settings.
   - **Shizuku** — green `Ready` (with server version) when everything works; orange `Waiting for authorization` with an **Authorize now** button that triggers the Shizuku approval dialog in place; orange `Service not running` with an **Open Shizuku** button plus wireless-debugging guidance; grey `Not installed` with a button to the Shizuku download page.
3. The section shows the live state of both channels plus which one is active (use Recheck after toggling).
4. Enable control actions for taps and typing; optionally fill the allowlist with package names to fence the agent in.
5. The Shizuku switch additionally unlocks the privileged shell and raw-input tools; the section shows the live binder state (not installed / not running / needs permission / ready).

## Troubleshooting

- **`binding the privileged service timed out`** — the Shizuku server accepted the bind request but never started Kai's privileged process (`<package>:privshell`). Notably, this happens *before* any command runs, so it is never caused by `uiautomator`, `input`, or anything the tool asked for. The original cause was a contract violation on Kai's side: Shizuku instantiates the user-service class itself and casts it to `IBinder`, so a class that extends `android.app.Service` (rather than implementing `IBinder`) fails that cast, the server returns null, and the bind times out with no other symptom. The class is now a `Binder` subclass and no longer appears in the manifest. Remaining checklist: Shizuku app installed and service started; Kai approved in Shizuku's authorized-apps list (revoke and re-grant once if unsure); retry — the client retries the bind once automatically. Still stuck: capture `adb logcat -s KaiShizuku KaiPrivShell:* Shizuku:*` during a retry. If `KaiPrivShell` lines never appear, the server never spawned the process — that is a Shizuku-server/ROM issue, not a Kai bug; if they do appear, paste them with the report.
- **`process name suffix must not be null`** (historical) — the client never sent the bind at all; fixed by always setting the remote process suffix.
- **First privileged call after granting Shizuku permission is slow or times out** — the 60-second tool timeout covers the permission dialog wait; approve promptly and retry, subsequent calls skip the wait.

## Limitations

- Reads need no per-app permission, but some apps (banking, DRM video) hide their UI from accessibility services entirely — those screens come back empty.
- Input supports Unicode on both channels. The accessibility path writes the text with `ACTION_SET_TEXT` after focusing the field (and resolves the editable descendant when a dump hands back the wrapping row); if a custom field or WebView rejects set-text, the text is placed on the system clipboard and pasted with `ACTION_PASTE`. The Shizuku path sends ASCII through `input text` and non-ASCII/CJK by writing the clipboard from the Kai process and sending `KEYCODE_PASTE` (279). Spaces are still encoded as `%s` for the ASCII path; quotes pass through verbatim since arguments go straight to exec without a shell.
- Only one UiAutomation client can exist system-wide: while instrumented UI tests run, `uiautomator dump` fails and the Shizuku channel reports it instead of hanging.
- System overlays, secure keyboards, and lock-screen content are unreachable by design.
- Gestures are single-stroke taps and swipes; multi-finger gestures (pinch) are not exposed.
- The privileged shell runs outside the Linux sandbox as the shell user (or root when Shizuku runs rooted) — treat its commands with the same care as a terminal.
- The privileged shell is the adb and Shizuku path, so the assistant never needs to hunt for device binaries from the sandbox shell. Other apps' private data directories remain unreadable unless Shizuku runs rooted; that denial is reported rather than retried.

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/src/androidMain/.../automation/KaiAccessibilityService.kt` | System accessibility service: element tree, gestures, screenshots, event ring |
| `composeApp/src/androidMain/.../automation/AutomationNodeRegistry.kt` | Short-lived element-id cache bridging tree walks and actions |
| `composeApp/src/androidMain/.../automation/AutomationController.kt` | Policy-gated facade all automation tools call into; routes per call over accessibility or Shizuku |
| `composeApp/src/androidMain/.../automation/ShellUiBackend.kt` | Shizuku UI channel: `uiautomator` tree dumps plus raw `input` actions |
| `composeApp/src/commonMain/.../tools/ShellUiDumpParser.kt` | Pure-Kotlin parsing of dump XML, foreground package, display size, input escaping |
| `composeApp/src/androidMain/.../automation/ShizukuController.kt` | Shizuku binder state, permission request, privileged process execution |
| `composeApp/src/commonMain/.../tools/AutomationPolicy.kt` | Sensitive blocklist and allowlist verdicts (shared, unit-tested) |
| `composeApp/src/androidMain/.../tools/UiAutomationTools.kt` | The seven agent-facing tool definitions |
| `composeApp/src/commonMain/.../ui/settings/AutomationSection.kt` | Settings → Agent → Automation UI |
| `androidApp/src/main/AndroidManifest.xml` | Service and Shizuku provider declarations |
