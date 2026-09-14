package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.LearnedSoulStore
import com.inspiredandroid.kai.data.MemoryStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_promote_learning_description
import kai.composeapp.generated.resources.tool_promote_learning_name

object HeartbeatTools {

    fun promoteLearningTool(memoryStore: MemoryStore, appSettings: AppSettings) = object : Tool {
        private val learnedSoulStore = LearnedSoulStore(appSettings)

        override val schema = ToolSchema(
            name = "promote_learning",
            description = "Promote a well-established memory into the permanent learned section of the system prompt. Use this for memories reinforced ${MemoryStore.PROMOTION_THRESHOLD} or more times (heartbeat surfaces these as promotion candidates) that should become permanent behavior.",
            parameters = mapOf(
                "memory_key" to ParameterSchema(type = "string", description = "The key of the memory to promote", required = true),
                "soul_addition" to ParameterSchema(type = "string", description = "The text to add to the learned section of the system prompt", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val memoryKey = args["memory_key"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "Missing memory_key")
            val soulAddition = args["soul_addition"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "Missing soul_addition")

            val memories = memoryStore.getAllMemories()
            val memory = memories.find { it.key == memoryKey }
                ?: return mapOf("success" to false, "error" to "Memory not found: $memoryKey")

            if (soulAddition in appSettings.getSoulText()) {
                return mapOf("success" to false, "error" to "This text is already in the soul — nothing to promote")
            }
            // Promoted habits live in their own capped store, not in the user's
            // editable soul — resetting the soul must not erase them.
            if (!learnedSoulStore.append(memoryKey, soulAddition)) {
                return mapOf("success" to false, "error" to "This learning is already promoted — nothing to promote")
            }

            // Remove the promoted memory
            memoryStore.forget(memoryKey)

            return buildMap<String, Any> {
                put("success", true)
                put("promoted_key", memoryKey)
                put("hit_count", memory.hitCount)
                put("message", "Memory promoted to the learned soul. Original memory removed.")
                if (memory.hitCount < MemoryStore.PROMOTION_THRESHOLD) {
                    put("warning", "Promoted after only ${memory.hitCount} reinforcement(s); heartbeat surfaces memories reinforced ${MemoryStore.PROMOTION_THRESHOLD}+ times as candidates. Promote early only deliberately.")
                }
            }
        }
    }

    val promoteLearningToolInfo = ToolInfo(
        id = "promote_learning",
        name = "Promote Learning",
        description = "Promote a reinforced learning into the learned section of the system prompt",
        nameRes = Res.string.tool_promote_learning_name,
        descriptionRes = Res.string.tool_promote_learning_description,
        userToggleable = false,
    )

    val heartbeatToolDefinitions = listOf(promoteLearningToolInfo)

    fun getHeartbeatTools(memoryStore: MemoryStore, appSettings: AppSettings): List<Tool> = listOf(
        promoteLearningTool(memoryStore, appSettings),
    )
}
