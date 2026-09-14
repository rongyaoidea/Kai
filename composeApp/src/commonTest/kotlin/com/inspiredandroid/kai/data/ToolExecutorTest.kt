package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
}
