package com.inspiredandroid.kai.ui.chat

import app.cash.turbine.test
import com.inspiredandroid.kai.data.Conversation
import com.inspiredandroid.kai.data.TaskScheduler
import com.inspiredandroid.kai.testutil.FakeDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the heartbeat conversation's dedicated entry point: it is hidden from the
 * chat history list, opening it clears the unread badge, and deleting it is deferred
 * with a race guard so a fresh report can't be dropped by a stale swipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatEntryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeDataRepository

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeDataRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        val scheduler = TaskScheduler(repository, enabled = false)
        return ChatViewModel(repository, scheduler, testDispatcher)
    }

    private fun message(id: String, content: String) = Conversation.Message(id = id, role = "assistant", content = content)

    private fun heartbeatConversation(
        updatedAt: Long = 10L,
        messages: List<Conversation.Message> = listOf(message("h1", "report")),
    ) = Conversation(
        id = "hb",
        messages = messages,
        createdAt = 1L,
        updatedAt = updatedAt,
        type = Conversation.TYPE_HEARTBEAT,
    )

    private fun chatConversation() = Conversation(
        id = "c1",
        messages = listOf(message("m1", "hi")),
        createdAt = 1L,
        updatedAt = 5L,
        type = Conversation.TYPE_CHAT,
    )

    @Test
    fun `heartbeat conversation is excluded from the history summaries`() = runTest {
        repository.savedConversations.value = listOf(heartbeatConversation(), chatConversation())
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.savedConversations.isEmpty()) state = awaitItem()

            assertEquals(listOf("c1"), state.savedConversations.map { it.id })
            assertEquals("hb", state.heartbeatConversationId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening the heartbeat conversation clears unread`() = runTest {
        repository.savedConversations.value = listOf(heartbeatConversation())
        repository.hasUnreadHeartbeat.value = true
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.heartbeatConversationId == null) state = awaitItem()

            state.actions.openHeartbeat()
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(repository.hasUnreadHeartbeat.value)
            assertEquals("hb", repository.currentConversationId.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread is cleared while the heartbeat conversation is open`() = runTest {
        repository.savedConversations.value = listOf(heartbeatConversation())
        repository.currentConversationId.value = "hb"
        repository.hasUnreadHeartbeat.value = true

        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(repository.hasUnreadHeartbeat.value)
    }

    @Test
    fun `deleting the heartbeat conversation clears unread and defers removal`() = runTest {
        repository.savedConversations.value = listOf(heartbeatConversation())
        repository.hasUnreadHeartbeat.value = true
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.heartbeatConversationId == null) state = awaitItem()
            assertTrue(repository.hasUnreadHeartbeat.value)

            state.actions.deleteHeartbeatConversation()
            testDispatcher.scheduler.runCurrent()

            assertFalse(repository.hasUnreadHeartbeat.value)
            assertTrue(repository.savedConversations.value.any { it.id == "hb" })

            var deleting = awaitItem()
            while (deleting.pendingConversationDeletion == null) deleting = awaitItem()
            assertEquals("hb", deleting.pendingConversationDeletion)

            testDispatcher.scheduler.advanceTimeBy(4_100)
            testDispatcher.scheduler.runCurrent()
            assertTrue(repository.savedConversations.value.none { it.id == "hb" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new report during the undo window keeps the heartbeat conversation`() = runTest {
        repository.savedConversations.value = listOf(heartbeatConversation())
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            testDispatcher.scheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.heartbeatConversationId == null) state = awaitItem()

            state.actions.deleteHeartbeatConversation()
            testDispatcher.scheduler.runCurrent()

            repository.savedConversations.update { list ->
                list.map {
                    if (it.id == "hb") {
                        it.copy(messages = it.messages + message("h2", "fresh"), updatedAt = 20L)
                    } else {
                        it
                    }
                }
            }

            testDispatcher.scheduler.advanceTimeBy(4_100)
            testDispatcher.scheduler.runCurrent()

            assertTrue(repository.savedConversations.value.any { it.id == "hb" })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
