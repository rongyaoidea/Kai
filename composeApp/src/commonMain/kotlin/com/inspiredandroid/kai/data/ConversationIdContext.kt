package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.ui.chat.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ConversationIdElement(val conversationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationIdElement>
}

suspend fun currentConversationIdOrNull(): String? = coroutineContext[ConversationIdElement]?.conversationId

/**
 * The message list an in-flight chat run writes to. Runs carry their own flow so
 * navigating away can't redirect progress into (or clobber) whatever conversation
 * the user is viewing next.
 */
class ChatRunHistoryElement(val history: MutableStateFlow<List<History>>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatRunHistoryElement>
}

suspend fun currentRunHistoryOrNull(): MutableStateFlow<List<History>>? = coroutineContext[ChatRunHistoryElement]?.history
