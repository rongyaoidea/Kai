package com.inspiredandroid.kai.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillInstallSourceTest {

    @Test
    fun `github input resolves to github source`() {
        val result = parseSkillInstallInput(github = "anthropics/skills/skills/pdf", url = null, content = null)
        assertTrue(result.isSuccess)
        assertEquals(SkillSource.GitHub("anthropics", "skills", "main", "skills/pdf"), result.getOrThrow())
    }

    @Test
    fun `github wins over url and content`() {
        val result = parseSkillInstallInput(
            github = "owner/repo",
            url = "https://example.com/SKILL.md",
            content = "---\nname: x\ndescription: y\n---\nbody\n",
        )
        assertTrue(result.isSuccess)
        assertIs<SkillSource.GitHub>(result.getOrThrow())
    }

    @Test
    fun `url input resolves to direct url source`() {
        val result = parseSkillInstallInput(github = null, url = "https://example.com/skills/foo/SKILL.md", content = null)
        assertTrue(result.isSuccess)
        assertEquals(SkillSource.DirectUrl("https://example.com/skills/foo/SKILL.md"), result.getOrThrow())
    }

    @Test
    fun `url wins over content`() {
        val result = parseSkillInstallInput(github = " ", url = "https://example.com/SKILL.md", content = "body")
        assertTrue(result.isSuccess)
        assertIs<SkillSource.DirectUrl>(result.getOrThrow())
    }

    @Test
    fun `non-http url fails`() {
        val result = parseSkillInstallInput(github = null, url = "ftp://example.com/SKILL.md", content = null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `content input resolves to inline source`() {
        val md = "---\nname: my-skill\ndescription: Does things\n---\nDo the thing.\n"
        val result = parseSkillInstallInput(github = null, url = null, content = md)
        assertTrue(result.isSuccess)
        assertEquals(SkillSource.Inline(md.trim()), result.getOrThrow())
    }

    @Test
    fun `empty everything fails with guidance`() {
        val result = parseSkillInstallInput(github = null, url = "  ", content = "")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("github") == true)
    }

    @Test
    fun `unparseable github fails`() {
        val result = parseSkillInstallInput(github = "onlyowner", url = null, content = null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `buildSkillFromContent validates frontmatter`() {
        val md = "---\nname: my-skill\ndescription: Does things\n---\nDo the thing.\n"
        val result = SkillRegistry().buildSkillFromContent(md)
        assertTrue(result.isSuccess)
        val skill = result.getOrThrow()
        assertEquals("my-skill", skill.id)
        assertEquals("Does things", skill.description)
        assertEquals(md, skill.rawSkillMd)
        assertTrue(skill.files.isEmpty())
    }

    @Test
    fun `buildSkillFromContent rejects bad frontmatter`() {
        assertTrue(SkillRegistry().buildSkillFromContent("no frontmatter here").isFailure)
        assertTrue(SkillRegistry().buildSkillFromContent("   ").isFailure)
    }
}
