package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationSearchTest {

    private fun message(role: String, content: String) = Conversation.Message(
        id = role,
        role = role,
        content = content,
    )

    private fun conversation(
        id: String,
        title: String,
        type: String = Conversation.TYPE_CHAT,
        updatedAt: Long = 0L,
        messages: List<Conversation.Message> = emptyList(),
    ) = Conversation(id = id, messages = messages, createdAt = 0L, updatedAt = updatedAt, title = title, type = type)

    @Test
    fun `title and message matches rank above non-matches`() {
        val conversations = listOf(
            conversation("a", "Grocery list", messages = listOf(message("user", "buy milk"))),
            conversation("b", "Linux sandbox setup", messages = listOf(message("assistant", "installed Debian"))),
        )

        val ranked = ConversationTools.rankConversations("Debian", conversations)
        assertEquals(listOf("b"), ranked.map { it.conversation.id })
        assertEquals(1, ranked.single().snippets.size)
    }

    @Test
    fun `heartbeat chatter is demoted below real chats`() {
        val conversations = listOf(
            conversation(
                "auto",
                "Heartbeat",
                type = Conversation.TYPE_HEARTBEAT,
                messages = listOf(message("assistant", "HEARTBEAT_OK nothing about Debian here Debian Debian")),
            ),
            conversation(
                "real",
                "Weekend plans",
                messages = listOf(message("user", "one mention of Debian")),
            ),
        )

        val ranked = ConversationTools.rankConversations("Debian", conversations)
        assertEquals(listOf("real", "auto"), ranked.map { it.conversation.id })
    }

    @Test
    fun `automation history stays findable when it is the only match`() {
        val conversations = listOf(
            conversation(
                "auto",
                "Heartbeat",
                type = Conversation.TYPE_HEARTBEAT,
                messages = listOf(message("assistant", "scheduled task completed")),
            ),
        )

        val ranked = ConversationTools.rankConversations("scheduled", conversations)
        assertEquals(listOf("auto"), ranked.map { it.conversation.id })
    }

    @Test
    fun `blank queries and empty stores yield empty`() {
        assertTrue(ConversationTools.rankConversations("  ", emptyList()).isEmpty())
        assertTrue(
            ConversationTools.rankConversations(
                "Debian",
                listOf(conversation("a", "Other", messages = listOf(message("user", "nothing relevant")))),
            ).isEmpty(),
        )
    }
}
