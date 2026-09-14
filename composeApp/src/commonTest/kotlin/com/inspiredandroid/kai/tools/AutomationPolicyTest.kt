package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutomationPolicyTest {

    @Test
    fun allowsOrdinaryAppsWithEmptyAllowlist() {
        assertEquals(
            AutomationPolicy.Verdict.Allow,
            AutomationPolicy.checkInteract("com.example.notes", emptySet()),
        )
    }

    @Test
    fun blocksBankingAndPasswordApps() {
        val verdict = AutomationPolicy.checkInteract("com.example.mybankapp", emptySet())
        assertIs<AutomationPolicy.Verdict.Deny>(verdict)
        val password = AutomationPolicy.checkInteract("com.bitwarden.authenticator", emptySet())
        assertIs<AutomationPolicy.Verdict.Deny>(password)
        val installer = AutomationPolicy.checkInteract("com.android.packageinstaller", emptySet())
        assertIs<AutomationPolicy.Verdict.Deny>(installer)
    }

    @Test
    fun blocklistBeatsAllowlist() {
        val verdict = AutomationPolicy.checkInteract(
            "com.example.paywallet",
            setOf("com.example.paywallet"),
        )
        assertIs<AutomationPolicy.Verdict.Deny>(verdict)
    }

    @Test
    fun allowlistRestrictsToListedPackages() {
        val allowed = setOf("com.example.notes")
        assertEquals(
            AutomationPolicy.Verdict.Allow,
            AutomationPolicy.checkInteract("com.example.notes", allowed),
        )
        assertIs<AutomationPolicy.Verdict.Deny>(
            AutomationPolicy.checkInteract("com.example.other", allowed),
        )
    }

    @Test
    fun deniesUnknownForegroundPackage() {
        assertIs<AutomationPolicy.Verdict.Deny>(AutomationPolicy.checkInteract(null, emptySet()))
        assertIs<AutomationPolicy.Verdict.Deny>(AutomationPolicy.checkInteract("", emptySet()))
    }
}
