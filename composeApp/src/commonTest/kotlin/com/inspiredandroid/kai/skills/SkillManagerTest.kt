package com.inspiredandroid.kai.skills

import com.inspiredandroid.kai.testutil.FakeSandboxController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory [SkillStore] standing in for the native tier
 * (`kai-native/skills` on a real device).
 */
private class FakeNativeSkillStore : SkillStore {
    val files = mutableMapOf<String, String>()

    private fun prefix(folder: String) = "$folder/"

    override suspend fun listFolders(): List<String> = files.keys.mapNotNull { path ->
        if (!path.contains('/')) null else path.substringBefore('/')
    }.distinct()

    override suspend fun listFiles(folder: String): List<String> = files.keys
        .filter { it.startsWith(prefix(folder)) }
        .mapNotNull { it.removePrefix(prefix(folder)).takeIf { rest -> !rest.contains('/') } }

    override suspend fun read(folder: String, name: String): String? = files["$folder/$name"]

    override suspend fun write(folder: String, relativePath: String, content: String): Boolean {
        files["$folder/$relativePath"] = content
        return true
    }

    override suspend fun delete(folder: String) {
        files.keys.filter { it.startsWith(prefix(folder)) }.forEach { files.remove(it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SkillManagerTest {

    private fun skillMd(name: String, desc: String = "desc", body: String = "Do the thing.") = "---\nname: $name\ndescription: $desc\n---\n$body\n"

    private fun manager(sandbox: FakeSandboxController) = SkillManager(sandbox, backgroundDispatcher = UnconfinedTestDispatcher())

    @Test
    fun `load reads installed skill folders with their files`() = runTest {
        val sandbox = FakeSandboxController()
        sandbox.files["/root/skills/foo/SKILL.md"] = skillMd("foo", body = "Body of foo.")
        sandbox.files["/root/skills/foo/helper.py"] = "print('hi')"
        val mgr = manager(sandbox)

        mgr.load()

        // The built-in `create-skill` ships in compose resources and is always loaded too;
        // filter it out so this test asserts only on what landed from the sandbox.
        val sandboxSkills = mgr.getInstalled().filterNot { it.isBuiltIn }
        assertEquals(1, sandboxSkills.size)
        assertEquals("foo", sandboxSkills[0].id)
        assertEquals("Body of foo.", sandboxSkills[0].body.trim())
        assertEquals(listOf("helper.py"), sandboxSkills[0].bundledFilePaths)
    }

    @Test
    fun `load ignores folders without a SKILL_md`() = runTest {
        val sandbox = FakeSandboxController()
        sandbox.files["/root/skills/nope/readme.md"] = "no skill here"
        val mgr = manager(sandbox)

        mgr.load()

        assertTrue(mgr.getInstalled().none { !it.isBuiltIn })
    }

    @Test
    fun `install writes the folder and surfaces the skill`() = runTest {
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)

        val result = mgr.install(
            DownloadedSkill(
                id = "bar",
                description = "desc",
                rawSkillMd = skillMd("bar"),
                files = mapOf("a.txt" to "x", "core/b.py" to "y"),
            ),
        )

        assertEquals("bar", result.id)
        assertEquals("x", sandbox.files["/root/skills/bar/a.txt"])
        assertEquals("y", sandbox.files["/root/skills/bar/core/b.py"])
        // Only top-level files are listed as bundled paths; nested dirs are not.
        assertEquals(listOf("a.txt"), mgr.getSkill("bar")?.bundledFilePaths)
    }

    @Test
    fun `reinstall replaces the previous folder contents`() = runTest {
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)

        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("old.txt" to "1")))
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("new.txt" to "2")))

        assertNull(sandbox.files["/root/skills/bar/old.txt"])
        assertEquals("2", sandbox.files["/root/skills/bar/new.txt"])
        assertEquals(listOf("new.txt"), mgr.getSkill("bar")?.bundledFilePaths)
    }

    @Test
    fun `uninstall deletes the folder`() = runTest {
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("a.txt" to "x")))

        mgr.uninstall("bar")

        assertTrue(mgr.getInstalled().none { !it.isBuiltIn })
        assertTrue(sandbox.files.keys.none { it.startsWith("/root/skills/bar/") })
    }

    @Test
    fun `getSkill and uninstall are case-insensitive`() = runTest {
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)
        mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), emptyMap()))

        assertEquals("bar", mgr.getSkill("BAR")?.id)
        mgr.uninstall("BAR")

        assertTrue(sandbox.files.keys.none { it.startsWith("/root/skills/bar/") })
        assertNull(mgr.getSkill("bar"))
    }

    @Test
    fun `uninstall deletes a hand-written folder whose name differs from the skill id`() = runTest {
        // The agent can write ~/skills/<anything>/SKILL.md via execute_shell_command;
        // uninstall must find the directory by parsed id, not by the folder name.
        val sandbox = FakeSandboxController()
        sandbox.files["/root/skills/my-custom-dir/SKILL.md"] = skillMd("handmade")
        sandbox.files["/root/skills/my-custom-dir/note.txt"] = "keep?"
        val mgr = manager(sandbox)
        mgr.load()

        mgr.uninstall("HANDMADE")

        assertTrue(sandbox.files.keys.none { it.startsWith("/root/skills/my-custom-dir/") })
        assertNull(mgr.getSkill("handmade"))
    }

    @Test
    fun `built-in skills load with valid frontmatter`() = runTest {
        val mgr = manager(FakeSandboxController())

        mgr.load()

        for (id in listOf("create-skill", "report", "hallmark", "finesse-ui")) {
            val skill = mgr.getSkill(id)
                ?: error("built-in skill missing: $id")
            assertTrue(skill.isBuiltIn)
            assertTrue(skill.description.isNotEmpty())
            assertTrue(skill.body.isNotBlank())
        }
    }

    @Test
    fun `reload picks up skill folders written outside install`() = runTest {
        // Simulates the agent creating ~/skills/<id>/SKILL.md directly through
        // execute_shell_command (the create-skill flow): no install() call runs,
        // so only an explicit reload can surface it.
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)
        mgr.load()
        assertNull(mgr.getSkill("handmade"))

        sandbox.files["/root/skills/handmade/SKILL.md"] = skillMd("handmade")
        mgr.load()

        assertEquals("handmade", mgr.getSkill("handmade")?.id)
    }

    @Test
    fun `skills flow emits reloaded skills for UI observers`() = runTest {
        val sandbox = FakeSandboxController()
        val mgr = manager(sandbox)
        mgr.load()
        assertTrue(mgr.skills.value.none { it.id == "handmade" })

        sandbox.files["/root/skills/handmade/SKILL.md"] = skillMd("handmade")
        mgr.load()

        assertTrue(mgr.skills.value.any { it.id == "handmade" })
    }

    @Test
    fun `without sandbox the native store backs prompt-only skills`() = runTest {
        val native = FakeNativeSkillStore()
        native.files["foo/SKILL.md"] = skillMd("foo", body = "Body of foo.")
        native.files["foo/notes.txt"] = "bundled"
        val mgr = SkillManager(
            FakeSandboxController(installed = false),
            nativeStore = native,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )

        mgr.load()

        val skill = mgr.getSkill("foo") ?: error("native skill missing")
        assertEquals(SkillTier.NATIVE, skill.tier)
        assertEquals(listOf("notes.txt"), skill.bundledFilePaths)
        assertTrue(mgr.hasStorage())
    }

    @Test
    fun `install writes to the native store when the sandbox is absent`() = runTest {
        val native = FakeNativeSkillStore()
        val mgr = SkillManager(
            FakeSandboxController(installed = false),
            nativeStore = native,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )

        val result = mgr.install(DownloadedSkill("bar", "desc", skillMd("bar"), mapOf("a.txt" to "x")))

        assertEquals("bar", result.id)
        assertEquals(SkillTier.NATIVE, result.tier)
        assertEquals("x", native.files["bar/a.txt"])
    }

    @Test
    fun `sandbox copies win over native ones on id collision`() = runTest {
        val native = FakeNativeSkillStore()
        native.files["foo/SKILL.md"] = skillMd("foo", body = "Native body.")
        val sandbox = FakeSandboxController()
        sandbox.files["/root/skills/other-folder/SKILL.md"] = skillMd("foo", body = "Sandbox body.")
        val mgr = SkillManager(sandbox, nativeStore = native, backgroundDispatcher = UnconfinedTestDispatcher())

        mgr.load()

        val skill = mgr.getSkill("foo") ?: error("skill missing")
        assertEquals(SkillTier.SANDBOX, skill.tier)
        assertEquals("Sandbox body.", skill.body.trim())
    }

    @Test
    fun `no stores at all yields no skills`() = runTest {
        val mgr = SkillManager(
            FakeSandboxController(installed = false),
            nativeStore = null,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )

        mgr.load()

        assertTrue(mgr.getInstalled().isEmpty())
        assertTrue(!mgr.hasStorage())
    }

    @Test
    fun `uninstall removes the folder from whichever store holds it`() = runTest {
        val native = FakeNativeSkillStore()
        native.files["bar/SKILL.md"] = skillMd("bar")
        val mgr = SkillManager(
            FakeSandboxController(installed = false),
            nativeStore = native,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )
        mgr.load()

        mgr.uninstall("bar")

        assertTrue(native.files.keys.none { it.startsWith("bar/") })
        assertNull(mgr.getSkill("bar"))
    }
}
