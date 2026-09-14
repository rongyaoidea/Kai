package com.inspiredandroid.kai.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Puts [text] on the system clipboard. This is the delivery mechanism for any
 * text Android's `input text` command can't carry: the shell command mangles
 * non-ASCII/CJK, and some custom fields (e.g. an app's search box or a WebView)
 * reject `ACTION_SET_TEXT` but accept a paste. The clipboard itself is Unicode-
 * clean, so pasting works wherever an IME could enter the text.
 *
 * Writing the clipboard is allowed from the background (unlike reading, which is
 * focus-gated since Android 10); the target app pastes it into the focused field.
 */
fun setSystemClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Kai", text))
}

/** Broadcast/raw keycode for paste; the platform `input` tool accepts the number. */
const val KEYCODE_PASTE = "279"
