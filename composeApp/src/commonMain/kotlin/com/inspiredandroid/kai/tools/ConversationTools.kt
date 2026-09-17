package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.Conversation
import com.inspiredandroid.kai.data.ConversationStorage
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema

internal data class ConversationMatch(
    val conversation: Conversation,
    val score: Double,
    val snippets: List<ConversationSnippet>,
)

internal data class ConversationSnippet(
    val role: String,
    val excerpt: String,
)

object ConversationTools {
    private const val MAX_CONVERSATIONS = 5
    private const val MAX_SNIPPETS = 3
    private const val SNIPPET_CHARS = 200

    /**
     * Heartbeat (and other automation) conversations accumulate repetitive
     * scheduler vocabulary that dominates bare keyword ranking and starves out
     * the user's own chats. Demote — never exclude — so automation history
     * stays findable when it is the only match.
     */
    private const val AUTOMATION_DEMOTION = 0.25

    internal fun rankConversations(query: String, conversations: List<Conversation>): List<ConversationMatch> {
        val trimmed = query.trim().lowercase()
        val terms = trimmed.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return conversations.mapNotNull { conversation ->
            var score = 0.0
            for (term in terms) {
                if (term in conversation.title.lowercase()) score += 3
            }
            if (trimmed in conversation.title.lowercase()) score += 2
            val snippets = mutableListOf<ConversationSnippet>()
            for (message in conversation.messages) {
                val content = message.content.trim()
                if (content.isEmpty()) continue
                val lower = content.lowercase()
                var hits = 0
                for (term in terms) {
                    if (term in lower) hits++
                }
                if (hits == 0) continue
                score += hits
                if (trimmed in lower) score += 2
                if (snippets.size < MAX_SNIPPETS) {
                    snippets.add(ConversationSnippet(message.role, excerpt(content, terms)))
                }
            }
            if (score <= 0) return@mapNotNull null
            if (conversation.type != Conversation.TYPE_CHAT) score *= AUTOMATION_DEMOTION
            ConversationMatch(conversation, score, snippets)
        }.sortedWith(compareByDescending<ConversationMatch> { it.score }.thenByDescending { it.conversation.updatedAt })
            .take(MAX_CONVERSATIONS)
    }

    private fun excerpt(content: String, terms: List<String>): String {
        val lower = content.lowercase()
        val firstHit = terms.mapNotNull { term ->
            lower.indexOf(term).takeIf { it >= 0 }
        }.minOrNull() ?: 0
        val start = (firstHit - SNIPPET_CHARS / 2).coerceAtLeast(0)
        val window = content.drop(start).take(SNIPPET_CHARS)
        return (if (start > 0) "…" else "") + window.trim() + (if (start + SNIPPET_CHARS < content.length) "…" else "")
    }

    fun searchConversationsTool(storage: ConversationStorage) = object : Tool {
        override val schema = ToolSchema(
            name = "search_conversations",
            description = "Keyword-search past conversations (titles and message text) and return matching " +
                "chats with excerpts. Use it when the user references an earlier discussion — " +
                "a past decision, a file the assistant produced, an error already solved. " +
                "Automation chatter (heartbeat runs) ranks below real chats. " +
                "Returns conversation ids and titles, not full transcripts. " +
                "Capped at 5 conversations with up to 3 excerpts each — narrow the query when capped.",
            parameters = mapOf(
                "query" to ParameterSchema(type = "string", description = "Keywords to match against titles and messages", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"]?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                return mapOf("success" to false, "error" to "Missing query")
            }
            val matches = rankConversations(query, storage.conversations.value)
            return mapOf(
                "success" to true,
                "count" to matches.size,
                "conversations" to matches.map { match ->
                    mapOf(
                        "id" to match.conversation.id,
                        "title" to match.conversation.title.ifBlank { "Untitled" },
                        "updated_at" to match.conversation.updatedAt,
                        "snippet_count" to match.snippets.size,
                        "snippets" to match.snippets.map { snippet ->
                            mapOf("role" to snippet.role, "excerpt" to snippet.excerpt)
                        },
                    )
                },
            )
        }
    }

    val searchConversationsToolInfo = ToolInfo(
        id = "search_conversations",
        name = "Search Conversations",
        description = "Keyword-search past conversations and return matching chats",
        nameRes = null,
        descriptionRes = null,
    )
}
