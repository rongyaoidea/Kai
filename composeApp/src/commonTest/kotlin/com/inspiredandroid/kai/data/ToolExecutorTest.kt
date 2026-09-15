package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.tools.ToolApprovalGate
import com.inspiredandroid.kai.tools.UntrustedToolOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for the [ToolExecutor.executeTool] error and cancellation contract. Tool lookup is
 * injected via the `toolsProvider` constructor parameter, so no Koin container is needed.
 */
class ToolExecutorTest {

    private class FakeTool(
        name: String = "fake_tool",
        override val timeout: Duration = 30.minutes,
        private val block: suspend () -> Any,
    ) : Tool {
        override val schema = ToolSchema(name = name, description = "test tool", parameters = emptyMap())
        override suspend fun execute(args: Map<String, Any>): Any = block()
    }

    private fun executorWith(tool: Tool) = ToolExecutor(toolsProvider = { listOf(tool) })

    @Test
    fun `executeTool propagates CancellationException instead of returning an error result`() = runTest {
        val executor = executorWith(FakeTool { throw CancellationException("stop") })
        assertFailsWith<CancellationException> {
            executor.executeTool("fake_tool", "{}")
        }
    }

    @Test
    fun `executeTool is cooperatively cancellable while a tool is running`() = runTest {
        var completed = false
        val executor = executorWith(FakeTool { awaitCancellation() })
        val job = launch {
            executor.executeTool("fake_tool", "{}")
            completed = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(!completed)
    }

    @Test
    fun `executeTool reports a timeout as an error result`() = runTest {
        val executor = executorWith(
            FakeTool(timeout = 100.milliseconds) { delay(10.minutes) },
        )
        val result = executor.executeTool("fake_tool", "{}")
        assertTrue(result.contains("timed out"))
    }

    @Test
    fun `executeTool reports a generic exception as an error result`() = runTest {
        val executor = executorWith(FakeTool { throw IllegalStateException("boom") })
        val result = executor.executeTool("fake_tool", "{}")
        assertTrue(result.contains("Tool execution failed"))
    }

    @Test
    fun `JSON null arguments are dropped so tools see them as omitted`() = runTest {
        var seen: Map<String, Any>? = null
        val echo = object : Tool {
            override val schema = ToolSchema(name = "echo", description = "t", parameters = emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                seen = args
                return mapOf("success" to true)
            }
        }
        executorWith(echo).executeTool("echo", """{"account_id": null, "q": "x"}""")
        assertTrue(seen != null && "account_id" !in seen!!, "null arg should be dropped, got: $seen")
        assertTrue(seen?.get("q") == "x")
    }

    @Test
    fun `large integers survive argument parsing without precision loss`() = runTest {
        var seen: Map<String, Any>? = null
        val echo = object : Tool {
            override val schema = ToolSchema(name = "echo", description = "t", parameters = emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                seen = args
                return mapOf("success" to true)
            }
        }
        executorWith(echo).executeTool("echo", """{"uid": 9007199254740993}""")
        assertTrue(seen?.get("uid") == 9007199254740993L, "expected Long uid, got: $seen")
    }

    @Test
    fun `blank arguments are treated as an empty object`() = runTest {
        var seen: Map<String, Any>? = null
        val echo = object : Tool {
            override val schema = ToolSchema(name = "echo", description = "t", parameters = emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                seen = args
                return mapOf("success" to true)
            }
        }
        // Some providers send "" instead of "{}" for tools without parameters.
        executorWith(echo).executeTool("echo", "")
        assertTrue(seen != null && seen!!.isEmpty(), "blank args should parse as empty map, got: $seen")
    }

    @Test
    fun `null inside a nested array is dropped like a null object value`() = runTest {
        var seen: Map<String, Any>? = null
        val echo = object : Tool {
            override val schema = ToolSchema(name = "echo", description = "t", parameters = emptyMap())
            override suspend fun execute(args: Map<String, Any>): Any {
                seen = args
                return mapOf("success" to true)
            }
        }
        executorWith(echo).executeTool("echo", """{"ids": [1, null, 3], "meta": {"a": null, "b": "x"}}""")
        assertTrue(seen?.get("ids") == listOf(1, 3), "array null should be dropped, got: $seen")
        assertTrue(seen?.get("meta") == mapOf("b" to "x"), "object null should be dropped, got: $seen")
    }

    @Test
    fun `tool results are marked as untrusted for the model`() = runTest {
        val result = executorWith(FakeTool { "page contents" }).executeTool("fake_tool", "{}")

        assertTrue(result.startsWith(UntrustedToolOutput.OPEN), "result should open with the marker, got: $result")
        assertTrue(result.contains("page contents"))
        assertTrue(result.endsWith(UntrustedToolOutput.CLOSE), "result should close with the marker, got: $result")
    }

    @Test
    fun `error results are marked as untrusted too`() = runTest {
        val result = executorWith(FakeTool { throw IllegalStateException("boom") }).executeTool("fake_tool", "{}")

        assertTrue(result.startsWith(UntrustedToolOutput.OPEN))
        assertTrue(result.contains("Tool execution failed"))
    }

    @Test
    fun `risky tools do not run unattended when no one can approve`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "privileged_shell") {
                executed = true
                "ran"
            }
        val gate = ToolApprovalGate()
        val executor = ToolExecutor(toolsProvider = { listOf(tool) }, approvalGate = gate)

        val result = executor.executeTool("privileged_shell", "{}", interactive = false)

        assertFalse(executed, "an unattended run must not execute a privileged command")
        assertTrue(result.contains("needs the user's approval"), "got: $result")
        assertTrue(gate.pending.value == null, "no dialog should be raised for a background run")
    }

    @Test
    fun `risky tools wait for approval and run once granted`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "privileged_shell") {
                executed = true
                "ran"
            }
        val gate = ToolApprovalGate()
        val executor = ToolExecutor(toolsProvider = { listOf(tool) }, approvalGate = gate)
        var result: String? = null

        val job = launch { result = executor.executeTool("privileged_shell", """{"command": "pm list packages"}""", interactive = true) }
        runCurrent()

        assertFalse(executed, "the tool must not run before the user answers")
        val pending = gate.pending.value?.request
        assertTrue(pending != null, "the request should be waiting for the UI")
        assertTrue(pending.detail.contains("pm list packages"), "the dialog shows the exact command, got: ${pending.detail}")

        gate.approve(pending.id)
        job.join()

        assertTrue(executed)
        assertTrue(result?.contains("ran") == true)
    }

    @Test
    fun `a denied risky tool reports the denial instead of running`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "privileged_shell") {
                executed = true
                "ran"
            }
        val gate = ToolApprovalGate()
        val executor = ToolExecutor(toolsProvider = { listOf(tool) }, approvalGate = gate)
        var result: String? = null

        val job = launch { result = executor.executeTool("privileged_shell", "{}", interactive = true) }
        runCurrent()
        gate.deny(gate.pending.value?.request?.id ?: error("no pending request"))
        job.join()

        assertFalse(executed)
        assertTrue(result?.contains("denied") == true, "got: $result")
    }

    @Test
    fun `harmless tools never ask for approval`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "get_local_time") {
                executed = true
                "12:00"
            }
        val gate = ToolApprovalGate()
        val executor = ToolExecutor(toolsProvider = { listOf(tool) }, approvalGate = gate)

        val result = executor.executeTool("get_local_time", "{}", interactive = true)

        assertTrue(executed)
        assertTrue(result.contains("12:00"))
        assertTrue(gate.pending.value == null)
    }

    @Test
    fun `shell auto-approve runs without a dialog`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "execute_shell_command") {
                executed = true
                "ran"
            }
        val gate = ToolApprovalGate()
        val executor =
            ToolExecutor(
                toolsProvider = { listOf(tool) },
                approvalGate = gate,
                isShellAutoApproved = { true },
            )

        val result = executor.executeTool("execute_shell_command", "{}", interactive = true)

        assertTrue(executed)
        assertTrue(result.contains("ran"))
        assertTrue(gate.pending.value == null, "no dialog should be raised when auto-approved")
    }

    @Test
    fun `shell auto-approve still fails closed when unattended`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "execute_shell_command") {
                executed = true
                "ran"
            }
        val gate = ToolApprovalGate()
        val executor =
            ToolExecutor(
                toolsProvider = { listOf(tool) },
                approvalGate = gate,
                isShellAutoApproved = { true },
            )

        val result = executor.executeTool("execute_shell_command", "{}", interactive = false)

        assertFalse(executed, "background runs must never run shell unattended")
        assertTrue(result.contains("needs the user's approval"), "got: $result")
    }

    @Test
    fun `shell auto-approve does not cover mail and installs`() = runTest {
        var executed = false
        val tool =
            FakeTool(name = "compose_email") {
                executed = true
                "sent"
            }
        val gate = ToolApprovalGate()
        val executor =
            ToolExecutor(
                toolsProvider = { listOf(tool) },
                approvalGate = gate,
                isShellAutoApproved = { true },
            )
        var result: String? = null

        val job = launch { result = executor.executeTool("compose_email", "{}", interactive = true) }
        // getToolDisplayName suspends on resource IO, which a single runCurrent()
        // does not flush — pump the scheduler until the dialog is up.
        for (i in 0 until 100) {
            runCurrent()
            if (gate.pending.value != null) break
            testScheduler.advanceTimeBy(50)
        }
        gate.deny(gate.pending.value?.request?.id ?: error("mail must still ask"))
        job.join()

        assertFalse(executed)
        assertTrue(result?.contains("denied") == true, "got: $result")
    }
}
