# Skills

**Last verified:** 2026-09-17

Kai supports installable **skills**: reusable instruction bundles, modeled on Anthropic's [SKILL.md](https://github.com/anthropics/skills) format (now an open standard at [agentskills.io](https://agentskills.io)). A skill packages a name, a description, a body of instructions, and optional bundled files.

User-installed skills live in one of two homes on a **tiered store**: the Linux sandbox at `~/skills/<id>/` when it is installed, or the app-private native workspace at `kai-native/skills/<id>/` when it is not. Without a sandbox the system degrades to **prompt-only skills** — bodies still steer the model, bundled files still exist in the workspace, but steps needing packages, python, git or ssh cannot run and the prompt says so. The Skills UI is **Android-only** (the only platform with either home), but no longer requires the sandbox to be installed first. The user browses a curated set of skill marketplaces (or installs from any GitHub repo) and triggers a skill in chat by starting a message with its slash command.

In addition, Kai ships a small set of **built-in skills** loaded from app compose resources: `create-skill` (author new skills), `report` (PDF/Word/Excel/slides deliverables), `hallmark` (anti-slop web design), and `finesse-ui` (register-routed interface design). Built-ins are compact single-file adaptations written for Kai's tools (the `report` built-in falls back to a self-contained HTML deliverable when the sandbox is absent; `create-skill` installs through `install_skill` with pasted content instead of hand-writing files) — not verbatim upstream copies — appear in the Skills list whenever any skill home exists, are labeled as built-in, and are not removable through the UI. If a user installs a skill with the same id, the installed copy takes precedence over the built-in. Users who want the complete upstream design systems can install them with `install_skill` (`nutlope/hallmark`, `mouse-lin/finesse-skill`); the document skills (`pdf`, `docx`, `xlsx`, `pptx`) stay install-only from the Anthropic marketplace because their license forbids bundling.

## Concepts

### Skill

Most skills are a folder (`~/skills/<id>/` in the sandbox, or `kai-native/skills/<id>/` in the native workspace) containing a `SKILL.md` (and any other files). The `SKILL.md` frontmatter provides a `name` (the slash-command id) and a `description`; the markdown after the frontmatter is the instruction body. `SkillManager` keeps an in-memory cache merged from both homes — native first, then sandbox (sandbox copies win on id collision) — plus any built-ins that are not overridden by a same-id installed folder; the cache is reloaded after every install/uninstall, when the sandbox installed state flips, and at the end of every agent turn (so folders the agent wrote directly through the shell show up without a restart). Chat and Settings observe the cache as a flow rather than snapshotting it. On platforms with neither home (desktop, iOS, web) the cache is simply always empty — skills never appear off-Android.

A skill has an id (lowercase letters, digits, and hyphens; ≤ 64 chars), a display name derived from the id, the instruction body, the list of its other top-level file names (surfaced in the prompt), and a **tier** (`SANDBOX` or `NATIVE`) recording which home it came from. There is no enable/disable state: an installed skill is active. Uninstall deletes the folder from whichever home holds it; built-ins have no remove action.

### Slash command

In chat, a message whose first token is `/<skill-id>` activates that skill for that single turn. The id must match an installed skill (case-insensitive). The user's message text is sent **verbatim** — it is not rewritten or stripped — so the conversation visibly reflects what was typed. The skill's instructions in the system prompt tell the model how to interpret any arguments after the slash command. Slash commands are opt-in: a message that doesn't match a skill behaves like any normal message.

### Active skill section

When a turn activates a skill, the skill's body is appended to the system prompt under an "Active skill" heading for that turn only. On every other turn the section adds zero bytes to the prompt. If the skill's folder has other files, their names are listed with path hints that follow the skill's tier: `~/skills/<id>/` in the Linux sandbox (sandbox tier) or `skills/<id>/` relative to the shell working directory, read with `read_file` (native tier). Native-tier skills also get a capability note that packages, python, git and ssh are unavailable, so the model doesn't burn turns on them. Nothing is materialized at chat time — the files are already in the active home.

### Marketplace

A marketplace is a public GitHub repo of skills. The browse list aggregates a small **curated, vetted** set of marketplaces (`curatedSkillMarketplaces`) — skills bundle scripts that run in the sandbox, so the suggested set favors trusted sources over breadth. Current sources:

- **Anthropic** ([anthropics/skills](https://github.com/anthropics/skills)) — a curated subset of the official repo that works well in Kai: document/data (pdf, docx, xlsx, pptx) and creative (algorithmic-art, slack-gif-creator). The Claude.ai/Claude-Code-oriented ones that don't translate to a mobile assistant (mcp-builder, skill-creator, theme-factory, web-artifacts-builder, webapp-testing, internal-comms, frontend-design, doc-coauthoring, canvas-design, brand-guidelines, claude-api) are excluded via the marketplace's `exclude` set.
- **Superpowers** ([obra/superpowers](https://github.com/obra/superpowers)) — the most popular Claude-skills repo, but a software-dev methodology, so only its broadly-useful "how to work" skills are surfaced via an allowlist (brainstorming, writing-plans); the Claude-Code-internal or coding-flow ones (git worktrees, code review, subagent dispatch, debugging, verification) are excluded.

A marketplace is read via the [Claude Code plugin-marketplace standard](https://github.com/anthropics/skills): an explicit per-source skill **allowlist** wins when set; otherwise a `.claude-plugin/marketplace.json`, when present, provides the authoritative skill list (and grouping); otherwise the registry falls back to scanning skill folders under the marketplace's `root` (default `skills/`). Any folder named in the source's **exclude** set is then dropped. In all cases a single recursive git-tree call per repo enumerates paths, and each `SKILL.md` is then fetched from `raw.githubusercontent.com` to keep within GitHub's unauthenticated API rate limit.

## Installing a Skill

The "Skills" section lives in the Tools tab of settings (Android only, below MCP servers).

- **Without the sandbox**, the section shows a one-line note that skills run as instructions in the native tier (packages/python/ssh unavailable) — everything else works the same.
- **"Add Skill"** opens a bottom sheet where the user can either paste a GitHub reference (`owner/repo`, `owner/repo/path/to/skill`, a full `https://github.com/owner/repo` URL, or a `.../tree/<ref>/path` URL) or **browse** the curated marketplaces. The browse list is fetched automatically when the dialog opens, is searchable (filter by id, description, or source), shows each entry's source name, and marks already-installed entries.

Both paths install the same way: a browsed entry carries its full repo coordinates and installs through the same GitHub fetch as a manual install. The **agent can install and remove skills itself** with `install_skill` / `list_skills` / `uninstall_skill` (Android; available whenever a skill home exists — sandbox or native; Tools-tab switches, default on): `install_skill` takes exactly one of `github` (the same `owner/repo` and URL forms as the dialog), `url` (a direct https link to a `SKILL.md` file on any host — GitHub is not required), or `content` (the full pasted `SKILL.md` text). GitHub installs pull sibling files; `url`/`content` installs are single-file. All three install through the same validated path, so size caps, frontmatter validation, and built-in overrides apply identically. When the repo file listing fails (rate limit, network), or the sibling cap (20 files) trims the set, the install still lands the `SKILL.md` but reports a warning so the missing siblings can be retried. A bare `owner/repo` paste installs the repo-root skill and only its root-level files — never the whole repository. Reinstalling replaces the folder and also removes a hand-written folder that carries the same skill id under a different directory name, so two copies can never shadow each other. Uninstall matches ids case-insensitively, like slash commands, and deletes the actual folder whose `SKILL.md` carries that id (hand-written folders may be named differently). The agent is told to use that tool rather than cloning into the skills folder by hand — a manual copy skips validation (a folder whose `SKILL.md` fails frontmatter parsing is silently ignored until fixed). Installing downloads the raw `SKILL.md` plus its sibling files (including nested ones; binaries and files over 256 KB are skipped) and writes them into the preferred home (`~/skills/<id>/` when the sandbox is installed, otherwise `kai-native/skills/<id>/`), replacing any existing folder, then reloads the cache. Install errors (invalid frontmatter, missing `SKILL.md`, unrecognized URL) are surfaced inline in the dialog.

## How the agent uses installed skills and MCP

Slash commands (`/<id>`) are user-side — the agent cannot slash-invoke. Instead, after installing (or when asked about) a skill, the agent follows it by reading `skills/<id>/SKILL.md` with `read_file` (the path is relative to the workspace home in both tiers; `list_skills` reports each skill's tier and bundled files). The `install_skill` result returns the `skill_file` path and the user-facing `invoke` (`/<id>`) so the agent can do both: follow the instructions itself now, and tell the user how to invoke it later. MCP is symmetric: `add_mcp_server` returns the exposed tool names in the same call, and newly discovered MCP tools default to enabled, so the agent can call them on the very next turn — no settings round-trip needed.

## Skill Management

Each skill card shows the slash command (`/<id>`) and the description; expanding a **user-installed** skill reveals a remove button. Removal is deferred with a snackbar "Undo" option before the folder is deleted, matching the MCP and service flows. Built-in skills show a Built-in badge and omit the remove action.

## Chat Autocomplete

While the user is typing the first token of a message and it starts with `/`, a dropdown of installed skills appears above the composer, filtered by the typed query. Selecting an entry rewrites the leading token to the canonical `/<id> ` and positions the cursor for follow-up arguments. The dropdown only triggers when the cursor is within the leading slash token.

## Limitations

- Android only — the two skill homes exist there; other platforms have neither. The Skills section is hidden off-Android.
- Without the sandbox, a skill whose instructions need packages, python, git or ssh degrades to guidance the model cannot fully execute; the prompt flags this to the model.
- Only text files are stored for GitHub installs; binaries and files over 256 KB are skipped, and a folder-scoped install pulls at most 20 siblings (a root-level install only the repo root's files). Direct-URL and pasted-text installs are single-file (`SKILL.md` only).
- The skill body is appended verbatim to the system prompt, so very large skills consume prompt budget for the turn they're active.
- GitHub browsing/installation requires network access and uses the unauthenticated GitHub API (subject to its rate limits).
- Built-in skills are not sandbox folders unless the user installs an override with the same id.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../skills/SkillManifest.kt` | `SkillManifest` (in-memory view of an installed skill), `DownloadedSkill`, `RegistrySkillEntry`, `SkillSource` |
| `composeApp/src/commonMain/.../skills/SkillMarketplaces.kt` | `SkillMarketplace` model + the curated, vetted marketplace list |
| `composeApp/src/commonMain/.../skills/SkillFrontmatterParser.kt` | SKILL.md frontmatter parser and id validation |
| `composeApp/src/commonMain/.../skills/SkillRegistry.kt` | Browses marketplaces (`.claude-plugin/marketplace.json`, git-tree discovery, raw SKILL.md fetch) and downloads a skill's files |
| `composeApp/src/commonMain/.../skills/SkillManager.kt` | Reads/installs/uninstalls skills across the two homes (sandbox `~/skills/`, native `kai-native/skills/`), merged cache with sandbox priority, GitHub URL parsing |
| `composeApp/src/commonMain/.../skills/SkillStore.kt` | `SkillStore` abstraction + sandbox implementation; `createNativeSkillStore()` expect factory |
| `composeApp/src/androidMain/.../skills/SkillStore.android.kt` | `NativeSkillStore` (app-private `kai-native/skills`, traversal-guarded) |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Appends the active-skill section to the system prompt |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Per-turn active-skill resolution (no materialization — files already in the sandbox) |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Parses the leading slash command into a skill id |
| `composeApp/src/commonMain/.../ui/chat/composables/QuestionInput.kt` | Detects the slash query while typing |
| `composeApp/src/commonMain/.../ui/chat/composables/SkillAutocomplete.kt` | Slash-command dropdown above the composer |
| `composeApp/src/commonMain/.../ui/settings/SkillsSection.kt` | Skill cards, native-tier note, and add-skill bottom sheet (GitHub + marketplace browse) |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Skill install/uninstall and browse UI state |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Shows the Skills section on Android via `sandboxState` |
