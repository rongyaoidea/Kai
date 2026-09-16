package com.inspiredandroid.kai.skills

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.TextFileResult

/**
 * Folder-oriented storage for skill folders — `<folder>/SKILL.md` plus bundled
 * files. Two implementations exist: the Linux sandbox (`/root/skills`, whenever
 * it is installed) and the native tier (app-private `kai-native/skills`, usable
 * with no sandbox at all). [SkillManager] merges both with sandbox copies
 * winning on id collision, so a sandbox-less install degrades to prompt-only
 * skills instead of losing the feature.
 */
interface SkillStore {
    /** Folder names directly under the skills root, unsorted. */
    suspend fun listFolders(): List<String>

    /** File names directly inside [folder] (no recursion). */
    suspend fun listFiles(folder: String): List<String>

    /** Contents of `[folder]/[name]`, or null when missing or unreadable. */
    suspend fun read(folder: String, name: String): String?

    /** Writes [content] to `[folder]/[relativePath]`, creating parent folders. */
    suspend fun write(folder: String, relativePath: String, content: String): Boolean

    /** Deletes [folder] and everything in it. Idempotent. */
    suspend fun delete(folder: String)
}

/** [SkillStore] over the sandbox file ops, rooted at [root] (`/root/skills`). */
class SandboxSkillStore(
    private val controller: SandboxController,
    private val root: String = SkillManager.SKILLS_DIR,
) : SkillStore {
    override suspend fun listFolders(): List<String> = controller.listDirectory(root).filter { it.isDirectory }.map { it.name }

    override suspend fun listFiles(folder: String): List<String> = controller.listDirectory("$root/$folder").filter { !it.isDirectory }.map { it.name }

    override suspend fun read(folder: String, name: String): String? = (controller.readTextFile("$root/$folder/$name") as? TextFileResult.Text)?.content

    override suspend fun write(folder: String, relativePath: String, content: String): Boolean = controller.writeTextFile("$root/$folder/$relativePath", content)

    override suspend fun delete(folder: String) {
        controller.deleteEntry("$root/$folder", recursive = true)
    }
}

/**
 * The native-tier skill store (app-private `kai-native/skills`) on platforms
 * that have one — Android, where it shares the native shell's home so bundled
 * files stay reachable with relative paths and `read_file`. Null everywhere
 * else, which keeps the skills feature Android-only as before.
 */
expect fun createNativeSkillStore(): SkillStore?
