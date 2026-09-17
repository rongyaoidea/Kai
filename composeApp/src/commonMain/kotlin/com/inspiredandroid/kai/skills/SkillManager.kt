package com.inspiredandroid.kai.skills

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.getBackgroundDispatcher
import kai.composeapp.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Manages the user's skills. Skill folders live in one of two homes: the Linux
 * sandbox at `~/skills/<id>/` when it is installed, or the app-private native
 * workspace at `kai-native/skills/<id>/` when it is not (Android only). Both are
 * read whenever available, with sandbox copies winning on id collision; a small
 * set of "built-in" skills ships inside the app as compose resources and fills
 * any remaining gaps. Without a sandbox the system degrades to prompt-only
 * skills — bodies still steer the model, but steps needing packages, python or
 * ssh cannot run.
 *
 * The cache is (re)loaded after every install/uninstall and whenever the sandbox
 * installed state flips. On platforms with no store at all (desktop, iOS, web)
 * `load()` yields an empty list, keeping the feature Android-only.
 */
class SkillManager(
    private val sandboxController: SandboxController,
    private val nativeStore: SkillStore? = createNativeSkillStore(),
    private val registry: SkillRegistry = SkillRegistry(),
    backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
) {

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val mutex = Mutex()

    private val _skills = MutableStateFlow<List<SkillManifest>>(emptyList())
    val skills: StateFlow<List<SkillManifest>> = _skills

    /**
     * Whether the most recent install may be missing bundled files because the
     * repo tree listing failed. Reset on every install; read it right after an
     * install call to warn instead of reporting a silent partial success.
     */
    var lastInstallWasIncomplete: Boolean = false
        private set

    init {
        // Reload when the sandbox installed state flips (which store is active
        // changes). Intermediate install-progress emissions don't reload.
        scope.launch {
            var lastInstalled: Boolean? = null
            sandboxController.status.collect { status ->
                if (lastInstalled != status.installed) {
                    lastInstalled = status.installed
                    load()
                }
            }
        }
    }

    fun getInstalled(): List<SkillManifest> = _skills.value

    // Ids are lowercase by frontmatter validation and slash commands resolve
    // case-insensitively, so lookups (including uninstall) do too.
    fun getSkill(id: String): SkillManifest? = _skills.value.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** True when at least one home can store skills (sandbox or native workspace). */
    fun hasStorage(): Boolean = nativeStore != null || sandboxController.status.value.installed

    /** Active homes in priority order: sandbox first (when installed), native last. */
    private fun activeStores(): List<Pair<SkillStore, SkillTier>> = buildList {
        if (sandboxController.status.value.installed) {
            add(SandboxSkillStore(sandboxController) to SkillTier.SANDBOX)
        }
        nativeStore?.let { add(it to SkillTier.NATIVE) }
    }

    suspend fun uninstall(id: String) {
        val manifest = getSkill(id)
        // Folders are normally named after the frontmatter id, but a folder the
        // agent wrote by hand may carry a different directory name — delete the
        // directory whose SKILL.md actually parses to this id, not a blind path.
        val dirName = manifest?.let { findDirectoryForSkill(it.id) } ?: id.lowercase()
        for ((store, _) in activeStores()) {
            if (store.listFolders().any { it == dirName }) store.delete(dirName)
        }
        load()
    }

    /** Directory name whose SKILL.md parses to [skillId], if any, across the active homes. */
    private suspend fun findDirectoryForSkill(skillId: String): String? {
        for ((store, _) in activeStores()) {
            for (dir in store.listFolders()) {
                val parsed = (store.read(dir, "SKILL.md"))?.let { SkillFrontmatterParser.parse(it) } as? SkillFrontmatterParser.Result.Ok
                    ?: continue
                if (parsed.id.equals(skillId, ignoreCase = true)) return dir
            }
        }
        return null
    }

    suspend fun installFromGitHub(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> = registry.fetchSkillFiles(SkillSource.GitHub(owner, repo, ref, path)).mapCatching { install(it) }

    /** Installs a single `SKILL.md` from a direct https URL (any host). No sibling files. */
    suspend fun installFromUrl(url: String): Result<SkillManifest> = registry.fetchSkillFiles(SkillSource.DirectUrl(url)).mapCatching { install(it) }

    /** Installs a skill from pasted `SKILL.md` text. Single-file, no siblings. */
    suspend fun installFromContent(content: String): Result<SkillManifest> = registry.fetchSkillFiles(SkillSource.Inline(content)).mapCatching { install(it) }

    /** Installs a skill the user picked from the browse list, using its repo coordinates. */
    suspend fun installFromRegistryEntry(entry: RegistrySkillEntry): Result<SkillManifest> = installFromGitHub(entry.owner, entry.repo, entry.ref, entry.skillPath)

    /** Browses the curated marketplaces and returns the combined, searchable list. */
    suspend fun browseMarketplaces(): Result<List<RegistrySkillEntry>> = registry.browseMarketplaces(curatedSkillMarketplaces)

    /**
     * Writes a downloaded skill into the preferred home (sandbox when installed,
     * the native workspace otherwise), replacing any existing copy, then reloads.
     */
    internal suspend fun install(downloaded: DownloadedSkill): SkillManifest {
        lastInstallWasIncomplete = downloaded.incomplete
        val (store, _) = activeStores().firstOrNull()
            ?: error("No skill storage available on this device")
        store.delete(downloaded.id) // replace if present
        // A hand-made folder may carry a different directory name for the same
        // id (see uninstall): remove it too, or load() flips between the two
        // copies depending on folder listing order.
        findDirectoryForSkill(downloaded.id)
            ?.takeIf { !it.equals(downloaded.id, ignoreCase = true) }
            ?.takeIf { stale -> store.listFolders().any { it == stale } }
            ?.let { store.delete(it) }
        store.write(downloaded.id, "SKILL.md", downloaded.rawSkillMd)
        for ((relPath, content) in downloaded.files) {
            val safe = relPath.split('/', '\\').filterNot { it.isEmpty() || it == ".." }
            if (safe.isEmpty()) continue
            store.write(downloaded.id, safe.joinToString("/"), content)
        }
        load()
        return getSkill(downloaded.id) ?: error("Skill '${downloaded.id}' not found after install")
    }

    /**
     * Reads every skill folder from the active homes back into the in-memory
     * cache. Lower-priority homes are read first so sandbox copies overwrite
     * native ones on id collision, and built-ins fill any remaining gaps.
     * No store (desktop/iOS/web) yields an empty list.
     */
    suspend fun load() {
        val skills = mutex.withLock {
            val sources = activeStores()
            if (sources.isEmpty()) return@withLock emptyList<SkillManifest>()
            val byId = LinkedHashMap<String, SkillManifest>()
            for ((store, tier) in sources.asReversed()) {
                for (skill in readStore(store, tier)) {
                    byId[skill.id] = skill
                }
            }
            for (builtIn in loadBuiltInSkills(sources.first().second)) {
                byId.putIfAbsent(builtIn.id, builtIn)
            }
            byId.values.sortedBy { it.id }
        }
        _skills.value = skills
    }

    private suspend fun readStore(store: SkillStore, tier: SkillTier): List<SkillManifest> = store.listFolders().mapNotNull { folder ->
        val md = store.read(folder, "SKILL.md") ?: return@mapNotNull null
        val parsed = SkillFrontmatterParser.parse(md) as? SkillFrontmatterParser.Result.Ok
            ?: return@mapNotNull null
        val files = store.listFiles(folder).filter { it != "SKILL.md" }.sorted()
        SkillManifest(
            id = parsed.id,
            displayName = SkillFrontmatterParser.displayName(parsed.id),
            description = parsed.description,
            body = parsed.body,
            bundledFilePaths = files,
            tier = tier,
        )
    }

    /**
     * Reads bundled SKILL.md files shipped in compose resources. They appear alongside
     * installed skills, can be invoked as `/<id>` from chat, and cannot be uninstalled.
     * Updates flow with each app release — nothing is persisted to a store. A built-in
     * whose resource read or frontmatter parse fails is silently dropped (no user-facing
     * failure for a missing/broken bundled asset). [tier] is the highest-priority active
     * home, so path hints in the prompt point where the model can actually read files.
     */
    private suspend fun loadBuiltInSkills(tier: SkillTier): List<SkillManifest> = BUILT_IN_SKILL_IDS.mapNotNull { id ->
        val bytes = runCatching { Res.readBytes("files/skills/$id/SKILL.md") }.getOrNull()
            ?: return@mapNotNull null
        val parsed = SkillFrontmatterParser.parse(bytes.decodeToString()) as? SkillFrontmatterParser.Result.Ok
            ?: return@mapNotNull null
        SkillManifest(
            id = parsed.id,
            displayName = SkillFrontmatterParser.displayName(parsed.id),
            description = parsed.description,
            body = parsed.body,
            isBuiltIn = true,
            tier = tier,
        )
    }

    companion object {
        /** Absolute sandbox path of the skills folder (`~/skills`, home = `/root`). */
        const val SKILLS_DIR = "/root/skills"

        /**
         * Ids of skills bundled in compose resources at
         * `composeResources/files/skills/<id>/SKILL.md`. Hardcoded so the asset path is
         * explicit at compile time and we don't need a resource directory listing.
         */
        private val BUILT_IN_SKILL_IDS = listOf("create-skill", "report", "hallmark", "finesse-ui")
    }
}

/**
 * Resolves the agent/UI install arguments into a [SkillSource]. Accepts exactly
 * one of: a GitHub reference (`owner/repo`, URL, or tree path), a direct https
 * URL to a `SKILL.md` file (any host), or pasted `SKILL.md` text. Pure so the
 * precedence and error messages can be unit-tested without the network.
 *
 * Precedence when several are given: `github` wins, then `url`, then `content`.
 */
fun parseSkillInstallInput(github: String?, url: String?, content: String?): Result<SkillSource> {
    val githubInput = github?.trim().orEmpty()
    val urlInput = url?.trim().orEmpty()
    val contentInput = content?.trim().orEmpty()
    if (githubInput.isNotEmpty()) {
        return parseGitHubSkillUrl(githubInput)?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("Could not parse \"$githubInput\" as a GitHub repo or URL"))
    }
    if (urlInput.isNotEmpty()) {
        if (!urlInput.startsWith("https://") && !urlInput.startsWith("http://")) {
            return Result.failure(IllegalArgumentException("\"$urlInput\" is not an http(s) URL"))
        }
        return Result.success(SkillSource.DirectUrl(urlInput))
    }
    if (contentInput.isNotEmpty()) {
        return Result.success(SkillSource.Inline(contentInput))
    }
    return Result.failure(IllegalArgumentException("Provide one of: github (owner/repo or URL), url (direct SKILL.md link), or content (pasted SKILL.md text)"))
}

/**
 * Parses several common forms users might paste to add a GitHub skill:
 * - `owner/repo`
 * - `owner/repo/path/to/skill`
 * - `https://github.com/owner/repo`
 * - `https://github.com/owner/repo/tree/<ref>/path/to/skill`
 *
 * Returns null on a shape we don't recognize so the dialog can surface a hint.
 */
fun parseGitHubSkillUrl(input: String): SkillSource.GitHub? {
    val trimmed = input.trim().substringBefore('?').substringBefore('#')
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.github.com/").removePrefix("github.com/")
    if (trimmed.isEmpty()) return null
    val parts = trimmed.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1].removeSuffix(".git")
    if (owner.isEmpty() || repo.isEmpty()) return null
    if (parts.size == 2) {
        return SkillSource.GitHub(owner = owner, repo = repo, ref = "main", path = "")
    }
    // owner/repo/tree/<ref>[/<path…>] or owner/repo/<path…> (assume main)
    return if (parts[2] == "tree" && parts.size >= 4) {
        val ref = parts[3]
        val path = parts.drop(4).joinToString("/")
        SkillSource.GitHub(owner = owner, repo = repo, ref = ref, path = path)
    } else {
        val path = parts.drop(2).joinToString("/")
        SkillSource.GitHub(owner = owner, repo = repo, ref = "main", path = path)
    }
}
