package com.inspiredandroid.kai.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAIResponsesSseAccumulatorTest {

    @Test
    fun `completed response wins and empty function-call arguments are repaired from deltas`() {
        val accumulator = OpenAIResponsesSseAccumulator()
        accumulator.accept("""{"type":"response.output_text.delta","delta":"Hello "}""")
        accumulator.accept("""{"type":"response.output_text.delta","delta":"world"}""")
        accumulator.accept("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":\""}""")
        accumulator.accept("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"/tmp/foo.txt\"}"}""")
        accumulator.accept("""{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","status":"completed","name":"read","call_id":"call_9","arguments":""}}""")
        accumulator.accept(
            """{"type":"response.completed","response":{"status":"completed","output":[""" +
                """{"id":"fc_1","type":"function_call","status":"incomplete","name":"read","call_id":"call_9","arguments":""},""" +
                """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello world"}]}]}}""",
        )

        val response = accumulator.build()
        assertEquals("Hello world", response.outputText)
        val call = response.functionCalls.single()
        assertEquals("read", call.name)
        assertEquals("call_9", call.callId)
        assertEquals("""{"path":"/tmp/foo.txt"}""", call.arguments)
    }

    @Test
    fun `truncated stream falls back to item and text deltas`() {
        val accumulator = OpenAIResponsesSseAccumulator()
        accumulator.accept("""{"type":"response.output_text.delta","delta":"partial answer"}""")
        accumulator.accept("""{"type":"response.function_call_arguments.done","item_id":"fc_2","name":"read","arguments":"{}"}""")
        accumulator.accept("""{"type":"response.output_item.added","item":{"id":"fc_2","type":"function_call","name":"read","call_id":"call_2"}}""")

        val response = accumulator.build()
        assertEquals("partial answer", response.outputText)
        val call = response.functionCalls.single()
        assertEquals("call_2", call.callId)
        assertEquals("{}", call.arguments)
    }

    @Test
    fun `failed events surface the error through throwIfFailed inputs`() {
        val accumulator = OpenAIResponsesSseAccumulator()
        accumulator.accept("""{"type":"response.failed","response":{"status":"failed","error":{"message":"boom","code":"server_error"}}}""")
        val response = accumulator.build()
        assertEquals("failed", response.status)
        assertEquals("boom", response.error?.message)
    }

    @Test
    fun `text done is used only when no deltas arrived`() {
        val accumulator = OpenAIResponsesSseAccumulator()
        accumulator.accept("""{"type":"response.output_text.done","text":"done only"}""")
        assertEquals("done only", accumulator.build().outputText)
    }

    @Test
    fun `malformed events are ignored`() {
        val accumulator = OpenAIResponsesSseAccumulator()
        accumulator.accept("oops")
        accumulator.accept("""{"type":"response.output_text.delta","delta":"ok"}""")
        assertEquals("ok", accumulator.build().outputText)
        assertNull(accumulator.build().error)
    }
}
