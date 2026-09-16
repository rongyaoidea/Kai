package com.inspiredandroid.kai.skills

import com.inspiredandroid.kai.getAppFilesDirectory
import java.io.File

/**
 * Native-tier skill storage: `filesDir/kai-native/skills`, deliberately the
 * same home the no-sandbox shell starts in, so a skill's bundled files are
 * reachable as `skills/<id>/<file>` both from the shell and from `read_file`.
 *
 * All paths are validated segments below [root]; traversal (`..`, absolute
 * components) is rejected rather than normalized.
 */
class NativeSkillStore(private val root: File) : SkillStore {

    override suspend fun listFolders(): List<String> = root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    override suspend fun listFiles(folder: String): List<String> = resolve(folder)?.listFiles().orEmpty().filter { it.isFile }.map { it.name }

    override suspend fun read(folder: String, name: String): String? = resolve(folder, name)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    override suspend fun write(folder: String, relativePath: String, content: String): Boolean {
        val file = resolve(folder, relativePath) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        }.getOrDefault(false)
    }

    override suspend fun delete(folder: String) {
        resolve(folder)?.deleteRecursively()
    }

    private fun resolve(vararg parts: String): File? {
        val segments = parts.flatMap { it.split('/', '\\') }.filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it == "." }) return null
        return segments.fold(root) { acc, segment -> File(acc, segment) }
    }
}

actual fun createNativeSkillStore(): SkillStore? = NativeSkillStore(File(getAppFilesDirectory(), "kai-native/skills"))
