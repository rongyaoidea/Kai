package com.inspiredandroid.kai.skills

/**
 * Where a skill's files live. [SANDBOX] is the full `/root/skills` home;
 * [NATIVE] is the app-private `kai-native/skills` workspace used when the
 * Linux sandbox is not installed — the same home the native shell starts in.
 */
enum class SkillTier { SANDBOX, NATIVE }

/**
 * An installed skill, read from its folder in the Linux sandbox at
 * `~/skills/<id>/` — or, with no sandbox, from the app-private native
 * workspace at `kai-native/skills/<id>/`. The home's filesystem is the single
 * source of truth — this is just the in-memory view loaded from `SKILL.md`
 * plus a listing of the folder's other files. [bundledFilePaths] are surfaced
 * in the system prompt so the model knows what's available; their contents
 * already live in that home and are read there.
 *
 * [isBuiltIn] marks skills that ship inside the app (loaded from compose
 * resources rather than a store). They are always available once any store
 * exists, cannot be uninstalled, and update with each app release.
 *
 * [tier] records which home [body] and [bundledFilePaths] refer to, so the
 * prompt can point at the right paths and warn when sandbox-only steps will
 * not work.
 */
data class SkillManifest(
    val id: String,
    val displayName: String,
    val description: String,
    val body: String,
    val bundledFilePaths: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val tier: SkillTier = SkillTier.SANDBOX,
)

/** Where a skill is downloaded from when installing into the sandbox. */
sealed class SkillSource {
    /** An arbitrary GitHub repo + folder path containing a `SKILL.md`. */
    data class GitHub(
        val owner: String,
        val repo: String,
        val ref: String = "main",
        val path: String,
    ) : SkillSource()

    /** A direct https URL to a `SKILL.md` file (any host, not just GitHub). Single-file install. */
    data class DirectUrl(val url: String) : SkillSource()

    /** Raw `SKILL.md` text pasted by the user or handed over by the agent. Single-file install. */
    data class Inline(val content: String) : SkillSource()
}

/**
 * The raw files of a skill downloaded from GitHub (or a direct URL / pasted
 * text), ready to be written into the sandbox at `~/skills/<id>/`.
 * [rawSkillMd] is stored verbatim as `SKILL.md`; [files] maps each sibling's
 * relative path to its text content.
 */
data class DownloadedSkill(
    val id: String,
    val description: String,
    val rawSkillMd: String,
    val files: Map<String, String> = emptyMap(),
    /**
     * True when the repo tree listing failed (e.g. GitHub rate limit) so
     * [files] may be missing siblings — the SKILL.md itself is intact.
     */
    val incomplete: Boolean = false,
)

/**
 * Lightweight record returned by the registry browse call — one installable skill
 * discovered in a marketplace. Carries the full GitHub coordinates so it can be
 * installed directly (via the same path as a manual GitHub install) and labeled
 * with its [sourceName] in the browse list.
 */
data class RegistrySkillEntry(
    val id: String,
    val description: String,
    val owner: String,
    val repo: String,
    val ref: String,
    val skillPath: String,
    val requiresSandbox: Boolean,
    val sourceName: String,
)
