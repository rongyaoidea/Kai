# Heartbeat

**Last verified:** 2026-09-13

> Heartbeat is user-controlled (on/off toggle, interval, active hours live in the settings UI). The AI cannot enable, disable, or reschedule it. To customise *what happens on each heartbeat*, the AI creates heartbeat-triggered scheduled tasks via `schedule_task` with `on_heartbeat: true` — these are `HEARTBEAT`-trigger tasks (see [tasks.md](tasks.md)) and their prompts are appended to every heartbeat run under `## Heartbeat Additions`. Each addition is a first-class task the user can see, edit, and cancel.

Kai's heartbeat feature enables periodic automatic self-checks. The AI reviews pending tasks, email status, newly arrived emails, and learned memories on a configurable interval, surfacing anything that needs attention without requiring user interaction.

## Concepts

### Heartbeat

A silent, scheduled prompt sent to the AI during active hours. If nothing needs attention, the AI responds with "HEARTBEAT_OK" and the user sees nothing. If something requires follow-up, the response appears as an assistant message in the chat.

### Active Hours

A configurable time window (default 8:00–22:00) during which heartbeats are allowed to fire. Outside this window, heartbeats are skipped regardless of interval.

### Promotion

A mechanism for graduating well-established memories into the permanent soul/system prompt. Memories that have been reinforced 5 or more times become promotion candidates and are surfaced during heartbeat checks for the AI to evaluate.

## Configuration

Heartbeat configuration is stored as a serialized JSON object in app settings. Values are only editable from the settings UI — there is no AI tool that can flip them:

- **Enabled**: true
- **Interval**: 30 minutes between heartbeats (UI slider offers 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h)
- **Active hours start**: 8 (hour, 24h format; UI range slider covers 0–23)
- **Active hours end**: 22 (hour, 24h format; UI range slider covers 0–24, where 24 renders as 0:00 and means end-of-day)
- **Model**: optional override for which service+model to use for heartbeats. When not set, the default path prefers the first configured **remote** service, then falls back to the first on-device (LiteRT) instance if no remote is configured. Useful for selecting a cheaper or faster model for background checks

UI validation rules:

- Interval must be at least 5 minutes
- Active hours start must be 0–23; end must be 0–24 (24 = midnight next day = full-day coverage)

## Execution Flow

1. The task scheduler polls every 60 seconds
2. On each poll, it checks: is the overall scheduling-enabled toggle on (the tasks setting that gates all scheduled work)? Is heartbeat enabled? Is the current hour within active hours? Has the configured interval elapsed since the last heartbeat?
3. If all conditions are met and no other API call is in progress, a heartbeat prompt is built and sent via `askWithTools` (which includes the full tool-calling loop)
4. The last heartbeat timestamp is updated and a log entry is recorded

Runs are single-flight: the poll loop and the manual settings refresh share a mutex, so a manual press while a heartbeat is already running returns without starting a second one. Heartbeat, scheduled-task, and notification-intent output all target the most recently updated heartbeat conversation, so an imported duplicate can't silently receive output the UI never shows.

When the overall scheduling-enabled toggle is off, heartbeats do not run regardless of the heartbeat toggle, interval, or active hours.

## Response Handling

- If the AI response contains "HEARTBEAT_OK", nothing is shown to the user
- Any other response is saved into a dedicated heartbeat conversation (type `heartbeat`) via `addAssistantMessage`
- **Android push notification**: when the heartbeat produces a non-OK report *and* the app is not currently in the foreground, a push notification fires. Foreground state is tracked via `ProcessLifecycleOwner` in `KaiApplication` and mirrored to `TaskScheduler.appInForeground`. Tapping the notification launches/foregrounds the app and deep-links into the heartbeat conversation via the `EXTRA_OPEN_HEARTBEAT` intent extra; `ChatViewModel` consumes the signal through `DataRepository.openHeartbeatRequested` and calls `loadConversation` on the heartbeat conversation id. The notification uses a fixed id so a fresh report replaces the previous unread one instead of stacking. The notification body is the heartbeat response with markdown formatting stripped and truncated to 240 characters at a word boundary (so the preview never breaks mid-word or shows raw markdown syntax). Desktop/iOS/web no-op (the in-app heartbeat button is the only surface).
- The heartbeat prompt is sent as a standalone message (not including user chat history as context)
- If the API call fails, a failure entry is recorded in the heartbeat log

## Entry Point

The heartbeat conversation is not listed in the chat history. It is reached through a floating heartbeat button anchored to the bottom-right of the chat, above the composer:

- **Visibility** — shown while heartbeat is enabled in settings, or whenever a heartbeat conversation exists (even if the feature was later turned off, so old reports stay reachable). Hidden while the user is already reading the heartbeat conversation, while it is being deleted, when the sandbox view is open, and in interactive mode. An empty heartbeat conversation still offers the top bar's new-chat button as a way out
- **Tap** — opens the heartbeat conversation (creating the record on first use) and clears the unread flag. Unread is cleared only by opening — never by dismissing a banner — and is persisted, so a report that arrived while the process was dead still shows its dot on the next launch
- **While open** — a report that lands while the user is already reading the heartbeat conversation is appended to the open chat live and does not mark unread
- **Idle** — quiet neutral button, no continuous animation
- **Unread** — the button turns to the tertiary container color with a dot badge, pops in once with a spring when a report arrives, and then loops a "heartbeat" animation: a two-beat scale pulse (two quick beats then a rest, 1.6s cycle) with an expanding halo ring
- **Running** — a gradient arc spins around the button while the heartbeat call is in flight (`TaskScheduler.isHeartbeatRunning`)
- **Delete** — swipe the button left (right in RTL layouts) past a threshold or long-press it. The button turns toward the error color as it is dragged and the shared pending-deletion mechanism shows an undo snackbar, matching history-sheet deletion. A heartbeat report that arrives during the undo window cancels the deletion instead of being dropped
- Heartbeat conversations keep their Message-list cap of 50 entries and can still be replied to like any conversation

## Prompt Building

The heartbeat prompt is assembled by the pure function `buildHeartbeatPrompt` (in `HeartbeatPromptBuilder.kt`). Each conditional section is covered by `HeartbeatPromptBuilderTest`. Sources:

1. **Custom prompt** — user-defined text from settings, or the default prompt if empty. The default instructs the AI to review memories and tasks, respond "HEARTBEAT_OK" if nothing needs attention, or address anything that does
2. **Previous heartbeat results** — the last 3 responses from the heartbeat conversation, so the AI can track trends, avoid repeating notifications, and detect persistent issues (e.g. "email still unread since last check")
3. **Pending tasks** — all tasks with status PENDING are listed with their description, id, scheduled time, and cron expression (if recurring)
4. **Email status** — if email is enabled and accounts exist, each account's email address, unread count, and last sync time are included
5. **New emails** — headers (subject, from, preview) for emails polled since the last heartbeat pickup. Emails are fetched in the background by the email poll loop and buffered in a pending queue (capped at 100, FIFO). The heartbeat consumes the queue: everything the heartbeat saw is removed from the queue after a successful run, while emails that arrive during the heartbeat call remain for the next run. After consumption the heartbeat also advances each account's delivery watermark, so a follow-up `check_email` call from the user won't re-surface the same messages — Kai tracks read/unread internally and ignores the provider's `\Seen` flag
6. **New SMS** — SMS messages received since the last heartbeat. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New SMS`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals
7. **New notifications** — Android notifications captured since the last heartbeat, capped at 20 newest-first. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New Notifications`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals
8. **Promotion candidates** — memories with 5 or more hits are listed with their key, hit count, category, and content, along with a suggestion to use the `promote_learning` tool
9. **Memory maintenance** — rotting rows (untouched past the staleness threshold, ≤1 hit, never preferences, oldest first, capped at 10) and possible duplicates (capped at 10 across 5 groups, best-written row kept) are listed with reasons and a suggestion to verify then act with `memory_forget`. Advisory only: the model decides, and preferences are never deleted without explicit user confirmation. Controlled by the **Memory maintenance** switch in the heartbeat settings card (on by default; the section only renders when candidates exist) plus a staleness slider (7/14/30/90/180 days, default 30). An **Auto-delete excess memories** switch (off by default) instead removes lowest-value rows itself — never preferences, never reinforced rows, oldest first, at most 20 per run — once the store passes 500 rows, and reports the removed keys in the same section

For the full contract of every prompt variation in Kai (chat remote/local, heartbeat, Splinterlands) see [system-prompts.md](system-prompts.md).

## Heartbeat Log

- Stores up to 5 most recent heartbeat entries
- Each entry records success/failure, a timestamp, and an optional error message
- Displayed in the settings UI under the heartbeat section
- Entries show an OK/FAIL indicator and a formatted local timestamp
- Failed entries display the error message (single line, ellipsized) below the timestamp in the error color

## Promote Learning

When a memory has been reinforced 5 or more times, it becomes a promotion candidate. The `promote_learning` tool:

1. Looks up the memory by key
2. Refuses when the text is already in the user's soul or already promoted (no duplicates)
3. Adds the provided `soul_addition` text to the AI-promoted **learned soul** (`LearnedSoulStore`) — kept apart from the user-authored soul, capped at 20 newest entries, removable in Settings → Agent → Soul. It renders as a `## Learned` section in the system prompt
4. Removes the original memory from the memory store
5. Returns confirmation with the promoted key and hit count — plus a warning when promoting before 5 reinforcements, since early promotion is only for deliberate cases

This allows well-established patterns to graduate from ephemeral memory into permanent AI behavior without polluting (or being erased by a reset of) the user's hand-written soul.

## Settings UI

The heartbeat section in settings contains:

- **Toggle** — enables or disables heartbeat with a switch
- **Interval display** — shows the current interval in minutes in the section description
- **Interval slider** — a snap-to-preset slider with positions for 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h. Displays the formatted value (e.g. "15m", "2h") next to the label
- **Active hours range slider** — a dual-thumb range slider spanning 0–24 (24-hour clock). Displays "H:00 – H:00" next to the label (unpadded hours); the upper-bound value 24 renders as 0:00 to indicate full-day coverage
- **Model picker** — a dropdown button showing the selected service+model, or "Default" when no override is set. Opens a dropdown menu listing all configured services with their icons and model IDs. Selecting "Default" clears the override and uses the remote-first default above. If the previously selected service is removed, heartbeat falls back to that default automatically
- **Custom prompt editor** — a text field (max 4000 characters) for editing the heartbeat prompt, with a save button that appears when changes are detected. Shows the default prompt text when no custom prompt is set. A character counter (X/4000) is displayed in the editor as the user types
- **Reset to default** — when a custom heartbeat prompt is set, a reset button appears in the custom-prompt section header. Tapping it opens a confirmation dialog; confirming clears the custom prompt and restores the default
- **Log display** — when log entries exist, shows a "Recent" label followed by each entry with an OK/FAIL indicator and timestamp
- **Manual refresh** — a refresh icon next to the "Recent" label runs a heartbeat immediately, bypassing the active-hours window and the interval-due check. Only fires while heartbeat is enabled and scheduling is on; the icon shows a progress spinner during the call

## AI Tools

| Tool | Purpose |
|---|---|
| `promote_learning` | Promote a reinforced memory into the soul/system prompt |

Standing additions to heartbeat behaviour are created with `schedule_task(on_heartbeat=true)` — see [tasks.md](tasks.md#heartbeat-triggered-tasks). Those prompts are appended to the main heartbeat self-check, not replaced.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/HeartbeatManager.kt` | Config, log management, wrapper that gathers inputs for the pure prompt builder |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/HeartbeatPromptBuilder.kt` | Pure heartbeat prompt assembly (sections, caps) |
| `composeApp/src/commonMain/.../tools/HeartbeatTools.kt` | AI tool definitions for heartbeat and promotion |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Poll loop that triggers single-flight heartbeat checks and exposes the running state |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persisted heartbeat config, prompt, log, and unread-badge storage |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Heartbeat conversation selection/creation, live append while open, unread flag management; default model selection for `askWithTools` |
| `composeApp/src/commonMain/.../ui/chat/composables/HeartbeatFab.kt` | Floating heartbeat entry point: unread/running/idle states, swipe-left and long-press delete |
| `composeApp/src/commonMain/.../ui/settings/HeartbeatSection.kt` | Heartbeat settings UI section |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Hosts the Agent tab that includes the heartbeat section |
| `composeApp/src/commonMain/.../Platform.kt` | `expect fun sendHeartbeatNotification` — push notification for background heartbeat reports |
| `composeApp/src/androidMain/.../HeartbeatNotifier.android.kt` | Android actual + `EXTRA_OPEN_HEARTBEAT` deep-link constant |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Reads `EXTRA_OPEN_HEARTBEAT` in `onCreate`/`onNewIntent` and calls `DataRepository.requestOpenHeartbeat` |
| `androidApp/src/main/kotlin/.../KaiApplication.kt` | Tracks foreground via `ProcessLifecycleOwner` and mirrors it to `TaskScheduler.appInForeground` |
