package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import com.inspiredandroid.kai.data.MemoryStore
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.openUrl
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_get_local_time_description
import kai.composeapp.generated.resources.tool_get_local_time_name
import kai.composeapp.generated.resources.tool_get_location_description
import kai.composeapp.generated.resources.tool_get_location_name
import kai.composeapp.generated.resources.tool_memory_forget_description
import kai.composeapp.generated.resources.tool_memory_forget_name
import kai.composeapp.generated.resources.tool_memory_learn_description
import kai.composeapp.generated.resources.tool_memory_learn_name
import kai.composeapp.generated.resources.tool_memory_reinforce_description
import kai.composeapp.generated.resources.tool_memory_reinforce_name
import kai.composeapp.generated.resources.tool_memory_store_description
import kai.composeapp.generated.resources.tool_memory_store_name
import kai.composeapp.generated.resources.tool_open_url_description
import kai.composeapp.generated.resources.tool_open_url_name
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
private data class IpLocationResponse(
    val ip: String? = null,
    val success: Boolean = false,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val postal: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val connection: IpConnectionInfo? = null,
    val timezone: IpTimezoneInfo? = null,
    val message: String? = null,
)

@Serializable
private data class IpConnectionInfo(
    val isp: String? = null,
    val org: String? = null,
)

@Serializable
private data class IpTimezoneInfo(
    val id: String? = null,
)

/**
 * Common tool definitions that work across all platforms.
 */
object CommonTools {

    private const val SEARCH_RESULT_LIMIT = 10
    private const val MEMORY_PREVIEW_CHARS = 200

    private val locationClient = httpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    val ipLocationTool = object : Tool {
        override val schema = ToolSchema(
            name = "get_location_from_ip",
            description = "Get the user's coarse, city-level estimated location based on their IP address (not GPS). Returns city, region, country, coordinates, and timezone.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any = try {
            val response: IpLocationResponse = locationClient.get("https://ipwho.is/").body()
            if (response.success) {
                mapOf(
                    "success" to true,
                    "city" to response.city,
                    "region" to response.region,
                    "country" to response.country,
                    "country_code" to response.countryCode,
                    "latitude" to response.latitude,
                    "longitude" to response.longitude,
                    "timezone" to response.timezone?.id,
                    "zip" to response.postal,
                    "isp" to response.connection?.isp,
                    "ip" to response.ip,
                )
            } else {
                mapOf(
                    "success" to false,
                    "error" to (response.message ?: "Failed to get location"),
                )
            }
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to "Failed to get location: ${e.message}",
            )
        }
    }

    val ipLocationToolInfo = ToolInfo(
        id = "get_location_from_ip",
        name = "Get Location",
        description = "Get estimated location from IP address",
        nameRes = Res.string.tool_get_location_name,
        descriptionRes = Res.string.tool_get_location_description,
    )

    val localTimeTool = object : Tool {
        override val schema = ToolSchema(
            name = "get_local_time",
            description = "Get the current local date and time. Call this first when the user mentions relative dates like 'tomorrow', 'next week', 'in 2 hours', etc.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val timeZone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            val localDateTime = now.toLocalDateTime(timeZone)

            // Format display string manually since kotlinx-datetime doesn't have formatters
            val dayOfWeek = localDateTime.dayOfWeek.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            val month = localDateTime.month.name.lowercase()
                .replaceFirstChar { it.uppercase() }
            val day = localDateTime.date.day
            val year = localDateTime.year
            val hour = localDateTime.hour
            val minute = localDateTime.minute.toString().padStart(2, '0')
            val amPm = if (hour < 12) "AM" else "PM"
            val hour12 = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }

            return mapOf(
                "iso_datetime" to "${localDateTime.date}T${localDateTime.hour.toString().padStart(2, '0')}:$minute:${localDateTime.second.toString().padStart(2, '0')}",
                "display_datetime" to "$dayOfWeek, $month $day, $year at $hour12:$minute $amPm",
                "timezone" to timeZone.id,
                "day_of_week" to localDateTime.dayOfWeek.name,
            )
        }
    }

    val localTimeToolInfo = ToolInfo(
        id = "get_local_time",
        name = "Get Local Time",
        description = "Get the current local date and time for interpreting relative dates",
        nameRes = Res.string.tool_get_local_time_name,
        descriptionRes = Res.string.tool_get_local_time_description,
    )

    val memoryStoreToolInfo = ToolInfo(
        id = "memory_store",
        name = "Store Memory",
        description = "Store or update a memory with a descriptive key",
        nameRes = Res.string.tool_memory_store_name,
        descriptionRes = Res.string.tool_memory_store_description,
        userToggleable = false,
    )

    val memoryForgetToolInfo = ToolInfo(
        id = "memory_forget",
        name = "Forget Memory",
        description = "Delete a stored memory by its key",
        nameRes = Res.string.tool_memory_forget_name,
        descriptionRes = Res.string.tool_memory_forget_description,
        userToggleable = false,
    )

    val memoryLearnToolInfo = ToolInfo(
        id = "memory_learn",
        name = "Learn Memory",
        description = "Store a categorized learning, error resolution, or preference",
        nameRes = Res.string.tool_memory_learn_name,
        descriptionRes = Res.string.tool_memory_learn_description,
        userToggleable = false,
    )

    val memoryReinforceToolInfo = ToolInfo(
        id = "memory_reinforce",
        name = "Reinforce Memory",
        description = "Reinforce a memory that produced a good outcome",
        nameRes = Res.string.tool_memory_reinforce_name,
        descriptionRes = Res.string.tool_memory_reinforce_description,
        userToggleable = false,
    )

    val memorySearchToolInfo = ToolInfo(
        id = "search_memories",
        name = "Search Memories",
        description = "Keyword-search stored memories and return matching keys",
        nameRes = null,
        descriptionRes = null,
        userToggleable = false,
    )

    val openUrlTool = object : Tool {
        override val schema = ToolSchema(
            name = "open_url",
            description = "Open a URL in the user's browser or default app. This ONLY opens the link for the user to view — you will NOT receive the page content back. Do not use this to fetch or read information from URLs. Use this when the user asks to open or visit a link. For files inside the Linux sandbox (/root) use open_file instead.",
            parameters = mapOf(
                "url" to ParameterSchema(type = "string", description = "The URL to open", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val url = args["url"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return mapOf("success" to false, "error" to "URL is required")
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("mailto:")) {
                return mapOf("success" to false, "error" to "only http, https, and mailto URLs can be opened")
            }
            return try {
                val opened = openUrl(url)
                if (opened) {
                    mapOf("success" to true, "url" to url, "message" to "URL opened successfully")
                } else {
                    mapOf("success" to false, "error" to "Failed to open URL")
                }
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to open URL: ${e.message}")
            }
        }
    }

    val openUrlToolInfo = ToolInfo(
        id = "open_url",
        name = "Open URL",
        description = "Open a URL or link on the device",
        nameRes = Res.string.tool_open_url_name,
        descriptionRes = Res.string.tool_open_url_description,
    )

    // Every tool the chat UI may have to name, on every platform. Entries whose availability is
    // decided by a master toggle in Settings → Agent (memory / scheduling / email / SMS /
    // notifications) or by a platform capability are marked `userToggleable = false` at their
    // declaration, so the Tools tab skips them instead of drawing a switch nothing reads.
    // They still belong here: without a definition, chat falls back to showing the raw tool id.
    val commonToolDefinitions = listOf(
        WebSearchTool.toolInfo,
        localTimeToolInfo,
        ipLocationToolInfo,
        openUrlToolInfo,
        FetchUrlTool.toolInfo,
        TodoTools.todoToolInfo,
    ) +
        McpAdminTools.toolInfos +
        listOf(memoryStoreToolInfo, memoryForgetToolInfo, memoryLearnToolInfo, memoryReinforceToolInfo, memorySearchToolInfo) +
        SchedulingTools.schedulingToolDefinitions +
        HeartbeatTools.heartbeatToolDefinitions +
        IntentTools.intentToolDefinitions +
        EmailTools.emailToolDefinitions +
        SmsTools.smsToolDefinitions +
        NotificationTools.notificationToolDefinitions

    fun getCommonTools(appSettings: AppSettings): List<Tool> = buildList {
        if (appSettings.isToolEnabled(localTimeTool.schema.name)) {
            add(localTimeTool)
        }
        if (appSettings.isToolEnabled(ipLocationTool.schema.name)) {
            add(ipLocationTool)
        }
        if (appSettings.isToolEnabled(WebSearchTool.schema.name)) {
            add(WebSearchTool)
        }
        if (appSettings.isToolEnabled(openUrlTool.schema.name)) {
            add(openUrlTool)
        }
        if (appSettings.isToolEnabled(FetchUrlTool.schema.name)) {
            add(FetchUrlTool)
        }
        if (appSettings.isToolEnabled(TodoTools.todoToolInfo.id)) {
            add(TodoTools.todoTool(TodoStore(appSettings)))
        }
    }

    // Memory tools - always enabled, core agent functionality

    fun memoryStoreTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "memory_store",
            description = "Store or update a memory with a descriptive key. Use this proactively to remember user preferences, facts, and important information across conversations.",
            parameters = mapOf(
                "key" to ParameterSchema(type = "string", description = "Descriptive key for the memory (e.g. user_name, preferred_language, project_details)", required = true),
                "content" to ParameterSchema(type = "string", description = "The content to store", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return mapOf("success" to false, "error" to "Missing key")
            val content = args["content"]?.toString()?.takeIf { it.isNotEmpty() } ?: return mapOf("success" to false, "error" to "Missing content")
            val entry = memoryStore.store(key, content)
            return mapOf("success" to true, "key" to entry.key, "content" to entry.content)
        }
    }

    fun memoryForgetTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "memory_forget",
            description = "Delete a stored memory by its exact key.",
            parameters = mapOf(
                "key" to ParameterSchema(type = "string", description = "The exact key of the memory to delete", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return mapOf("success" to false, "error" to "Missing key")
            val removed = memoryStore.forget(key)
            return mapOf("success" to removed, "key" to key)
        }
    }

    fun memoryLearnTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "memory_learn",
            description = "Store a structured learning with a category. Use LEARNING for things that worked, ERROR for error resolutions, PREFERENCE for user corrections/preferences.",
            parameters = mapOf(
                "key" to ParameterSchema(type = "string", description = "Descriptive key for the learning", required = true),
                "content" to ParameterSchema(type = "string", description = "What was learned", required = true),
                "category" to ParameterSchema(type = "string", description = "Category: LEARNING, ERROR, or PREFERENCE", required = true),
                "source" to ParameterSchema(type = "string", description = "How this was learned: user_correction, observation, or error_resolution", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            val content = args["content"]?.toString() ?: return mapOf("success" to false, "error" to "Missing content")
            val categoryStr = args["category"]?.toString()?.uppercase() ?: return mapOf("success" to false, "error" to "Missing category")
            val source = args["source"]?.toString()

            val category = try {
                MemoryCategory.valueOf(categoryStr)
            } catch (_: Exception) {
                return mapOf("success" to false, "error" to "Invalid category: $categoryStr. Use LEARNING, ERROR, or PREFERENCE")
            }

            if (category == MemoryCategory.GENERAL) {
                return mapOf("success" to false, "error" to "Use memory_store for GENERAL memories. memory_learn is for LEARNING, ERROR, or PREFERENCE")
            }

            val entry = memoryStore.store(key, content, category, source)
            return mapOf("success" to true, "key" to entry.key, "category" to entry.category.name, "content" to entry.content)
        }
    }

    fun memoryReinforceTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "memory_reinforce",
            description = "Reinforce a stored memory by incrementing its hit count. Use this when a stored learning or preference produced a good outcome.",
            parameters = mapOf(
                "key" to ParameterSchema(type = "string", description = "The exact key of the memory to reinforce", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val key = args["key"]?.toString() ?: return mapOf("success" to false, "error" to "Missing key")
            val entry = memoryStore.reinforceMemory(key)
                ?: return mapOf("success" to false, "error" to "Memory not found: $key")
            return mapOf("success" to true, "key" to entry.key, "hit_count" to entry.hitCount)
        }
    }

    fun getMemoryTools(memoryStore: MemoryStore): List<Tool> = listOf(
        memoryStoreTool(memoryStore),
        memoryForgetTool(memoryStore),
        memoryLearnTool(memoryStore),
        memoryReinforceTool(memoryStore),
        memorySearchTool(memoryStore),
    )

    /**
     * Keyword search over stored memories. No embeddings, no network — pure
     * substring scoring so it works on-device and in every language (CJK
     * queries match as substrings since they carry no word boundaries).
     * Key hits outrank content hits; an exact-phrase content hit outranks both.
     */
    internal fun rankMemories(query: String, entries: List<MemoryEntry>): List<MemoryEntry> {
        val terms = query.lowercase().split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            val key = entry.key.lowercase()
            val content = entry.content.lowercase()
            var score = 0
            for (term in terms) {
                if (term in key) score += 3
                if (term in content) score += 1
            }
            if (query.lowercase() in content) score += 2
            if (score > 0) entry to score else null
        }.sortedByDescending { it.second }.take(SEARCH_RESULT_LIMIT).map { it.first }
    }

    fun memorySearchTool(memoryStore: MemoryStore) = object : Tool {
        override val schema = ToolSchema(
            name = "search_memories",
            description = "Keyword-search stored memories and return matching keys with previews. " +
                "Use it when the user references something half-remembered, or when you need an exact " +
                "key for memory_reinforce / memory_forget instead of guessing one. " +
                "The full memory list is already in your prompt — this is for pinpointing, not browsing.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Keywords to match against memory keys and contents", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                return mapOf("success" to false, "error" to "Missing query")
            }
            val matches = rankMemories(query, memoryStore.getAllMemories())
            return mapOf(
                "success" to true,
                "count" to matches.size,
                "memories" to matches.map { entry ->
                    mapOf(
                        "key" to entry.key,
                        "category" to entry.category.name,
                        "preview" to entry.content.take(MEMORY_PREVIEW_CHARS),
                        "hit_count" to entry.hitCount,
                    )
                },
            )
        }
    }
}
