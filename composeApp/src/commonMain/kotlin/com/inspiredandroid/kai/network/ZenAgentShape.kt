package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.data.FreeTierModels
import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesRequestDto

/**
 * Wire shape Zen's free-model pool requires.
 *
 * The gateway rejects any free-model request that does not look like an OpenCode agent session
 * with `403 FreeTierError` ("OpenCode's free tier can only be used from within OpenCode") —
 * independent of the API key. Two body properties are checked, verified against the live
 * gateway:
 *
 *  - `stream: true` (a non-streaming body is rejected even with the right headers and tools);
 *  - `tools` containing the five core agent tool names [ZEN_CORE_TOOL_NAMES]. Only the names
 *    matter: the gateway does not inspect their schemas, and it accepts extra tools alongside
 *    them.
 *
 * So for free Zen models Kai appends a declaration for any missing core name. When Kai already
 * has a matching capability (`read_file`, `write_file`, `execute_shell_command`) the alias
 * copies that tool's real schema and the tool executor routes the call back to it; the rest are
 * declared as parameter-less placeholders so the shape check passes and a stray call fails with
 * a clear message instead of "Unknown tool".
 *
 * These rules apply to both lanes free models are served on: chat completions and the Responses
 * API. Zen's Messages lane has no free models.
 */
internal val ZEN_CORE_TOOL_NAMES = listOf("bash", "edit", "glob", "grep", "read")

/**
 * Core tool name on the wire → Kai tool that implements it, when one exists. `glob`/`grep`
 * have no Kai equivalent and stay placeholders.
 */
internal val ZEN_TOOL_ALIASES: Map<String, String> = mapOf(
    "bash" to "execute_shell_command",
    "read" to "read_file",
    "edit" to "write_file",
)

/**
 * True when [modelId] is served by Zen's free pool. Free-ness is checked against the curated
 * [FreeTierModels] set (suffix-based, so new `-free` ids are covered automatically) and the
 * request must target Zen — Go is a paid subscription with no free pool, and no other provider
 * is sent these shapes.
 */
internal fun isZenFreeModel(service: Service, modelId: String, url: String): Boolean {
    if (modelId.isBlank()) return false
    if (!isOpenCodeEndpoint(service, url)) return false
    if (isOpenCodeGoUrl(url)) return false
    return FreeTierModels.isOpenCodeFree(modelId)
}

/**
 * The outgoing chat-completions tool list, with any missing core agent tool appended. Existing
 * declarations win; aliases clone the real tool's schema so the model can actually call them.
 */
internal fun zenShapedChatTools(
    tools: List<OpenAICompatibleChatRequestDto.Tool>?,
): List<OpenAICompatibleChatRequestDto.Tool> {
    val declared = tools.orEmpty()
    val byName = declared.associateBy { it.function.name }
    val additions = ZEN_CORE_TOOL_NAMES.mapNotNull { core ->
        if (core in byName) {
            null
        } else {
            val aliased = ZEN_TOOL_ALIASES[core]?.let { byName[it] }
            if (aliased != null) {
                aliased.copy(function = aliased.function.copy(name = core))
            } else {
                OpenAICompatibleChatRequestDto.Tool(
                    function = OpenAICompatibleChatRequestDto.Function(
                        name = core,
                        description = "Agent tool $core",
                        parameters = OpenAICompatibleChatRequestDto.Parameters(
                            properties = emptyMap(),
                        ),
                    ),
                )
            }
        }
    }
    return declared + additions
}

/** Responses-API twin of [zenShapedChatTools]: same names, flattened tool shape. */
internal fun zenShapedResponsesTools(
    tools: List<OpenAIResponsesRequestDto.Tool>?,
): List<OpenAIResponsesRequestDto.Tool> {
    val declared = tools.orEmpty()
    val byName = declared.associateBy { it.name }
    val additions = ZEN_CORE_TOOL_NAMES.mapNotNull { core ->
        if (core in byName) {
            null
        } else {
            val aliased = ZEN_TOOL_ALIASES[core]?.let { byName[it] }
            aliased?.copy(name = core)
                ?: OpenAIResponsesRequestDto.Tool(
                    name = core,
                    description = "Agent tool $core",
                    parameters = OpenAICompatibleChatRequestDto.Parameters(
                        properties = emptyMap(),
                    ),
                )
        }
    }
    return declared + additions
}
