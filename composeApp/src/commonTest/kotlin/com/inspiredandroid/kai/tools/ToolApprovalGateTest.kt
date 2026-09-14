package com.inspiredandroid.kai.tools

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Contract of [ToolApprovalGate]: risky tools wait for a decision, and every way of not
 * getting one ends in a denial rather than a silent approval.
 */
class ToolApprovalGateTest {

    @Test
    fun `approve lets the waiting call through and clears the pending request`() = runTest {
        val gate = ToolApprovalGate(timeout = 1.minutes)
        var allowed: Boolean? = null

        val job = launch { allowed = gate.awaitApproval("privileged_shell", "Privileged Shell", "Run a shell command", "pm list packages") }
        runCurrent()

        val request = gate.pending.value?.request
        assertTrue(request != null, "the request should be exposed to the UI")
        assertEquals("privileged_shell", request.toolId)
        assertEquals("pm list packages", request.detail)

        gate.approve(request.id)
        job.join()

        assertTrue(allowed == true)
        assertNull(gate.pending.value)
    }

    @Test
    fun `deny reports a denial`() = runTest {
        val gate = ToolApprovalGate(timeout = 1.minutes)
        var allowed: Boolean? = null

        val job = launch { allowed = gate.awaitApproval("compose_email", "Compose Email", "Send an email", "{}") }
        runCurrent()
        gate.deny(gate.pending.value?.request?.id ?: error("no pending request"))
        job.join()

        assertFalse(allowed ?: true)
        assertNull(gate.pending.value)
    }

    @Test
    fun `an unanswered request times out into a denial`() = runTest {
        val gate = ToolApprovalGate(timeout = 30.seconds)
        var allowed: Boolean? = null

        val job = launch { allowed = gate.awaitApproval("execute_shell_command", "Execute Shell Command", "Run a shell command", "rm -rf /") }
        runCurrent()
        assertTrue(gate.pending.value != null)

        advanceTimeBy(31.seconds)
        job.join()

        assertFalse(allowed ?: true, "a request nobody answered must not be treated as approved")
        assertNull(gate.pending.value)
    }

    @Test
    fun `dismissing the dialog denies the pending request`() = runTest {
        val gate = ToolApprovalGate(timeout = 1.minutes)
        var allowed: Boolean? = null

        val job = launch { allowed = gate.awaitApproval("install_skill", "Install Skill", "Install a skill", "https://example.com/SKILL.md") }
        runCurrent()
        gate.dismissPending()
        job.join()

        assertFalse(allowed ?: true)
        assertNull(gate.pending.value)
    }

    @Test
    fun `answers for a different request id are ignored`() = runTest {
        val gate = ToolApprovalGate(timeout = 1.minutes)
        var allowed: Boolean? = null

        val job = launch { allowed = gate.awaitApproval("privileged_shell", "Privileged Shell", "Run a shell command", "ls") }
        runCurrent()
        gate.approve("tool-approval-999")
        runCurrent()

        assertNull(allowed, "a stale id must not answer the live request")
        assertTrue(gate.pending.value != null)

        gate.deny(gate.pending.value?.request?.id ?: error("no pending request"))
        job.join()
        assertFalse(allowed ?: true)
    }

    @Test
    fun `parallel calls ask one question at a time`() = runTest {
        val gate = ToolApprovalGate(timeout = 1.minutes)
        val answered = mutableListOf<String>()

        val first = launch { if (gate.awaitApproval("privileged_shell", "Privileged Shell", "Run a shell command", "one")) answered.add("first") }
        runCurrent()
        val second = launch { if (gate.awaitApproval("privileged_shell", "Privileged Shell", "Run a shell command", "two")) answered.add("second") }
        runCurrent()

        assertEquals("one", gate.pending.value?.request?.detail, "the second call must wait for the first to be answered")
        gate.approve(gate.pending.value?.request?.id ?: error("no pending request"))
        runCurrent()
        assertEquals("two", gate.pending.value?.request?.detail)
        gate.approve(gate.pending.value?.request?.id ?: error("no pending request"))

        first.join()
        second.join()
        assertEquals(listOf("first", "second"), answered)
    }
}
