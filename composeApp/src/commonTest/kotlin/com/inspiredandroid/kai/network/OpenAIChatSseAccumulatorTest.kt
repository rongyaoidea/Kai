package com.inspiredandroid.kai.network

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIChatSseAccumulatorTest {

    @Test
    fun `text, reasoning, and tool call fragments fold into one message`() {
        val accumulator = OpenAIChatSseAccumulator()
        // Frames mirror a real Zen free-model stream (nemotron-3.5-lightning-free).
        accumulator.accept("""{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"","reasoning":"We"}}]}""")
        accumulator.accept("""{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"","reasoning":" need"}}]}""")
        accumulator.accept(
            """{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":null,"tool_calls":""" +
                """[{"index":0,"id":"call-1","type":"function","function":{"name":"read","arguments":""}}]}}]}""",
        )
        accumulator.accept(
            """{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":null,"tool_calls":""" +
                """[{"index":0,"function":{"arguments":"{\"path\":\"/tmp/foo.txt\"}"}}]}}]}""",
        )
        accumulator.accept("""{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{"role":"assistant","content":""}}],"usage":{"total_tokens":326}}""")
        // The gateway appends this after [DONE]; the reader drops it, but accept() must survive it.
        accumulator.accept("""{"choices":[],"cost":"0"}""")

        val message = accumulator.build().choices.first().message!!
        assertEquals("We need", message.reasoningContent)
        assertNull(message.content)
        val call = message.toolCalls!!.single()
        assertEquals("call-1", call.id)
        assertEquals("read", call.function.name)
        assertEquals("""{"path":"/tmp/foo.txt"}""", call.function.arguments)
    }

    @Test
    fun `tool call fragments merge by index even when interleaved`() {
        val accumulator = OpenAIChatSseAccumulator()
        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"bash","arguments":"{\"c"}},""" +
                """{"index":1,"id":"b","function":{"name":"read","arguments":"{\"p"}}]}}]}""",
        )
        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"ath\":\"x\"}"}},""" +
                """{"index":0,"function":{"arguments":"md\":\"ls\"}"}}]}}]}""",
        )

        val calls = accumulator.build().choices.first().message!!.toolCalls!!
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals("""{"cmd":"ls"}""", calls[0].function.arguments)
        assertEquals("""{"path":"x"}""", calls[1].function.arguments)
    }

    @Test
    fun `reasoning_content is accepted as well as reasoning`() {
        val accumulator = OpenAIChatSseAccumulator()
        accumulator.accept("""{"choices":[{"delta":{"reasoning_content":"one "}}]}""")
        accumulator.accept("""{"choices":[{"delta":{"reasoning":"two"}}]}""")
        assertEquals("one two", accumulator.build().choices.first().message!!.reasoningContent)
    }

    @Test
    fun `malformed frames are ignored`() {
        val accumulator = OpenAIChatSseAccumulator()
        accumulator.accept("not json")
        accumulator.accept("""{"choices":[{"delta":{"content":"ok"}}]}""")
        assertEquals("ok", accumulator.build().choices.first().message!!.content)
    }

    @Test
    fun `empty stream builds an empty assistant message`() {
        val message = OpenAIChatSseAccumulator().build().choices.first().message!!
        assertNull(message.content)
        assertNull(message.toolCalls)
    }

    @Test
    fun `readSseData skips comments and stops at DONE`() = runTest {
        val body = """
            : keep-alive

            data: {"a":1}

            : keep-alive
            event: ping
            data: {"a":2}

            data: [DONE]

            data: {"late":true}
        """.trimIndent()
        val collected = mutableListOf<String>()
        ByteReadChannel(body).readSseData { collected.add(it) }
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), collected)
        assertTrue(collected.none { it.contains("late") })
    }
}
