package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.skills.SkillManager
import com.inspiredandroid.kai.skills.SkillSource
import com.inspiredandroid.kai.skills.parseSkillInstallInput
import org.koin.java.KoinJavaComponent.inject

/**
 * Lets the agent install and remove skills itself. Skills are folders in the
 * Linux sandbox (`~/skills/<id>/`) when it is installed, or in the app-private
 * native workspace (`kai-native/skills/<id>/`) otherwise — so these tools exist
 * whenever any skill home does, and installed skills appear in Settings →
 * Tools → Skills with no extra step. Without the sandbox the system degrades
 * to prompt-only skills; the SKILL.md files are still installed and followed.
 *
 * Installing pulls the `SKILL.md` (plus sibling text files for GitHub sources)
 * through the same path the UI uses, so validation, size caps, and built-in
 * overrides all behave identically.
 */
object SkillAdminTools {
    private val skillManager: SkillManager by inject(SkillManager::class.java)
    private val appSettings: AppSettings by inject(AppSettings::class.java)

    fun getTools(): List<Tool> {
        if (!skillManager.hasStorage()) return emptyList()
        return buildList {
            if (appSettings.isToolEnabled("list_skills")) add(listSkillsTool)
            // A skill is instruction text the agent then follows, fetched from a host the
            // model picks. Enabled by default per product decision (2026-09-14); the
            // approval gate still asks before each install/remove at runtime.
            if (appSettings.isToolEnabled("install_skill")) add(installSkillTool)
            if (appSettings.isToolEnabled("uninstall_skill")) add(uninstallSkillTool)
        }
    }

    /**
     * Path hint for the active tier: sandbox skills live under `/root/skills`,
     * native-tier ones under the shell working directory. `skills/<id>/...` is
     * correct for both when read through `read_file` — the sandbox file tools
     * take paths relative to `/root`, the native file tools relative to their
     * home, and both are the same home the skills store uses.
     */
    private fun skillFileHint(id: String): String = "skills/$id/SKILL.md"

    val listSkillsTool = object : Tool {
        override val schema = ToolSchema(
            name = "list_skills",
            description = "List installed skills with their id, description, and whether each is built in. The user invokes a skill by starting a message with /<id>; you yourself follow a skill by reading its instructions with read_file at skills/<id>/SKILL.md (bundled files are listed as files). Call this before installing so you reuse ids instead of creating duplicates.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any = mapOf(
            "success" to true,
            "skills" to skillManager.getInstalled().map {
                mapOf(
                    "id" to it.id,
                    "display_name" to it.displayName,
                    "description" to it.description,
                    "built_in" to it.isBuiltIn,
                    "tier" to it.tier.name.lowercase(),
                    "files" to it.bundledFilePaths,
                )
            },
        )
    }

    val installSkillTool = object : Tool {
        override val schema = ToolSchema(
            name = "install_skill",
            description = "Install a skill (stored in the Linux sandbox when installed, otherwise in Kai's native workspace — no sandbox required). Give exactly one of: github (owner/repo, a GitHub URL, or a tree path to a subfolder containing SKILL.md, e.g. anthropics/skills or anthropics/skills/tree/main/skills/pdf), url (a direct https link to a SKILL.md file on any host), or content (the full pasted SKILL.md text). GitHub installs include sibling files; url/content installs are single-file. Use this instead of writing skills/<id>/SKILL.md by hand — a manual copy skips validation and stays invisible until the skill list reloads. Reinstalling the same id replaces it. After installing, read skills/<id>/SKILL.md with read_file to follow its instructions, and tell the user what you installed — it shows up in Settings → Tools → Skills and the user invokes it with /<id>.",
            parameters = mapOf(
                "github" to ParameterSchema("string", "owner/repo, a GitHub URL, or a tree path to the skill folder", false),
                "url" to ParameterSchema("string", "Direct https link to a SKILL.md file on any host", false),
                "content" to ParameterSchema("string", "Full SKILL.md text to install as-is", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (!skillManager.hasStorage()) {
                return mapOf("success" to false, "error" to "No skill storage available on this device.")
            }
            val source = parseSkillInstallInput(
                github = args["github"]?.toString(),
                url = args["url"]?.toString(),
                content = args["content"]?.toString(),
            ).getOrElse { error ->
                return mapOf("success" to false, "error" to (error.message ?: "Invalid install arguments"))
            }
            val result = when (source) {
                is SkillSource.GitHub -> skillManager.installFromGitHub(source.owner, source.repo, source.ref, source.path)
                is SkillSource.DirectUrl -> skillManager.installFromUrl(source.url)
                is SkillSource.Inline -> skillManager.installFromContent(source.content)
            }
            return result.fold(
                onSuccess = { skill ->
                    buildMap<String, Any> {
                        put("success", true)
                        put("id", skill.id)
                        put("display_name", skill.displayName)
                        put("description", skill.description)
                        put("invoke", "/${skill.id}")
                        put("skill_file", skillFileHint(skill.id))
                        put("tier", skill.tier.name.lowercase())
                        if (skillManager.lastInstallWasIncomplete) {
                            put("warning", "Installed SKILL.md but the file listing failed (rate limit or network), so bundled files may be missing. Retry the install later to complete it.")
                        }
                    }
                },
                onFailure = { error ->
                    mapOf("success" to false, "error" to "Install failed: ${error.message}")
                },
            )
        }
    }

    val uninstallSkillTool = object : Tool {
        override val schema = ToolSchema(
            name = "uninstall_skill",
            description = "Remove an installed skill by its id. Built-in skills cannot be removed; the call reports that instead.",
            parameters = mapOf(
                "skill_id" to ParameterSchema("string", "Skill id from list_skills (the /<id> command name)", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (!skillManager.hasStorage()) {
                return mapOf("success" to false, "error" to "No skill storage available on this device.")
            }
            val id = args["skill_id"]?.toString()?.trim().orEmpty()
            if (id.isEmpty()) return mapOf("success" to false, "error" to "skill_id is required")
            val skill = skillManager.getSkill(id)
                ?: return mapOf("success" to false, "error" to "No installed skill with id=$id")
            if (skill.isBuiltIn) {
                return mapOf("success" to false, "error" to "Skill $id is built in and cannot be removed")
            }
            skillManager.uninstall(id)
            return mapOf("success" to true, "removed" to id)
        }
    }

    val toolInfos = listOf(
        ToolInfo(
            id = "list_skills",
            name = "List Skills",
            description = "List installed skills",
            isEnabled = true,
        ),
        ToolInfo(
            id = "install_skill",
            name = "Install Skill",
            description = "Install a skill from GitHub, a direct URL, or pasted text",
            isEnabled = true,
        ),
        ToolInfo(
            id = "uninstall_skill",
            name = "Uninstall Skill",
            description = "Remove an installed skill",
            isEnabled = true,
        ),
    )
}
