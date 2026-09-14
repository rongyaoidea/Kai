package com.inspiredandroid.kai.automation

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived cache mapping opaque string IDs (e.g. "a3f2") to live
 * [AccessibilityNodeInfo] references. The framework hands out fresh node objects
 * on every tree walk, so the agent references a node by ID seconds later in
 * `ui_act tap --node <id>`. Entries expire after [TTL_MS].
 *
 * Every entry also keeps a [NodeSignature] of the node's identifying attributes.
 * When the handle detaches (the screen changed after a tap), the controller can
 * re-locate the same element in the current tree by signature instead of forcing
 * the agent to re-dump.
 */
class AutomationNodeRegistry {
    data class NodeSignature(
        val resId: String?,
        val text: String?,
        val desc: String?,
        val className: String?,
    )

    private data class Entry(
        val node: AccessibilityNodeInfo,
        val createdAt: Long,
        val signature: NodeSignature,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    private val seq = AtomicLong(0)

    fun put(node: AccessibilityNodeInfo): String {
        evictExpired()
        val id = nextId()
        map[id] = Entry(node, System.currentTimeMillis(), signatureOf(node))
        return id
    }

    fun get(id: String): AccessibilityNodeInfo? {
        val entry = map[id] ?: return null
        // Expired entries are dropped by evictExpired() on the next dump; keep the
        // signature readable here so auto-relocation still has an anchor.
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return null
        return entry.node
    }

    /** Identity of the node behind [id], still readable after the entry expired. */
    fun signatureOf(id: String): NodeSignature? = map[id]?.signature

    fun clear() {
        map.clear()
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > TTL_MS) iterator.remove()
        }
    }

    private fun signatureOf(node: AccessibilityNodeInfo): NodeSignature = NodeSignature(
        resId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
        text = node.text?.toString()?.takeIf { it.isNotBlank() },
        desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        className = node.className?.toString()?.takeIf { it.isNotBlank() },
    )

    private fun nextId(): String = java.lang.Long.toString(seq.incrementAndGet() and 0xFFFFFL, 36).padStart(4, '0')

    companion object {
        const val TTL_MS = 300_000L
    }
}
