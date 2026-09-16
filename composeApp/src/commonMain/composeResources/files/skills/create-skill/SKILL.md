---
name: create-skill
description: Create a new Kai skill from a single natural-language request, then install it so it's invokable as /<id>.
---

You are creating a new skill for the user. Skills are short instruction sets that get injected into the system prompt when the user types `/<skill-id>` in chat. Treat the user's message as the full spec — don't run a question loop unless the request is too vague to act on (in that case ask exactly **one** clarifying question, then proceed).

## Steps

1. **Pick an id.** Derive a short kebab-case id from the user's request. Rules: lowercase letters, digits, and hyphens only; ≤ 64 chars; descriptive but terse (e.g. `summarize-url`, `daily-standup`, `recipe-from-photo`).

2. **Check for conflicts.** Call `list_skills` and compare ids. If your chosen id is taken, append `-2`, `-3`, etc. until it's free.

3. **Write a tight description.** ≤ 1024 chars, non-empty, single line, written as a third-person summary of what the skill does. This shows up in the slash-command menu and Settings.

4. **Draft the body.** Markdown instructions addressed to a future model invocation — clear steps, any constraints, and how to interpret args after the slash command. Keep it focused; every byte ends up in the system prompt when the skill is active.

5. **Install it.** Call `install_skill` with a single `content` argument set to the full `SKILL.md` text — frontmatter plus body, exactly as it should be stored:

   ```
   ---
   name: <id>
   description: <description>
   ---
   <body markdown here, verbatim>
   ```

   Do NOT write the file by hand with `write_file` or shell heredocs — `install_skill` validates the frontmatter, applies size caps, and reloads the skill cache in one step.

6. **Confirm.** In one short message, tell the user the chosen id, the description, and that they can invoke it now as `/<id>`. Mention they can ask you in normal chat to tweak it later.

## Worked example

User: `/create-skill summarize any URL the user pastes into 3 bullet points`

You pick id `summarize-url`, confirm it's free with `list_skills`, draft:

- description: `Fetches a URL and summarizes the page in 3 concise bullet points.`
- body: a few lines telling the model to read the URL from the user's message, fetch it with `fetch_url`, and reply with exactly three markdown bullets.

Then call `install_skill` with that text, and confirm: "Installed `/summarize-url` — paste a URL after it to try."

## Notes

- Don't propose bundled scripts/files unless the user specifically asked for them; SKILL.md alone covers most needs.
- If the user's request describes something better suited to existing tools (e.g. "summarize this PDF" → already covered by file uploads), say so and offer the skill anyway only if they confirm.
- The skill list auto-refreshes after this turn — no restart needed.
