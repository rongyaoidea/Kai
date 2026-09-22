package com.inspiredandroid.kai.ui.chat

/**
 * Whether a history append may pull the viewport to the newest item.
 *
 * Chat is a reader's surface: content can arrive while the user is reading older turns — most
 * visibly in the heartbeat conversation, whose background run mirrors its live history into the
 * viewed conversation (see `RemoteDataRepository` run mirroring). Auto-scrolling on every append
 * yanked readers to the bottom on each of those updates, so following is now opt-in per append:
 *
 *  - [neverLaidOut] is the first paint after opening a conversation (or the first message of a
 *    new chat): there is no reader position yet, so start at the newest item.
 *  - [userSubmitted] always follows — sending a message is an explicit "show me the reply".
 *  - [foregroundReply] covers the user's own run while it streams (placeholder, tool rows,
 *    answer). Background runs never set it, so a heartbeat appending to the conversation cannot
 *    move the viewport; the scroll-to-bottom button reports the new content instead.
 *  - [atBottom] keeps a foreground run from overriding a user who scrolled up to read mid-reply.
 */
internal fun shouldFollowNewestItem(
    neverLaidOut: Boolean,
    atBottom: Boolean,
    userSubmitted: Boolean,
    foregroundReply: Boolean,
): Boolean = neverLaidOut || userSubmitted || (foregroundReply && atBottom)
