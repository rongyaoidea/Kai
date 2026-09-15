package com.inspiredandroid.kai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tools that act outside the conversation — shell commands, outgoing mail, installing
 * MCP servers or skills — must be approved by the user before they run.
 *
 * This is deliberately code and not prompt text: a web page the agent just fetched can
 * talk the model into *calling* `privileged_shell`, but it cannot talk this policy out of
 * asking the user first.
 */
object ToolApprovalPolicy {
    private val approvals =
        mapOf(
            "privileged_shell" to "Run a shell command with ADB/root privileges",
            "execute_shell_command" to "Run a shell command in the Linux sandbox",
            "compose_email" to "Send an email",
            "reply_email" to "Send an email reply",
            "add_mcp_server" to "Connect a new MCP server",
            "remove_mcp_server" to "Disconnect an MCP server",
            "install_skill" to "Install a skill",
            "uninstall_skill" to "Remove a skill",
        )

    /** Why [toolId] needs approval, or null when it may run unattended. */
    fun approvalReason(toolId: String): String? = approvals[toolId]

    /**
     * Tools covered by the shell auto-approve setting (Settings → Tools):
     * the two ways the agent runs commands. Everything else in [approvals]
     * always asks — mail and installs are never silently allowed.
     */
    val shellTools = setOf("execute_shell_command", "privileged_shell")
}

/** One action waiting for the user's yes/no. */
data class ToolApprovalRequest(
    val id: String,
    val toolId: String,
    val toolName: String,
    val reason: String,
    /** The concrete arguments, so the user approves the command and not just the tool name. */
    val detail: String,
)

/**
 * Suspends the tool loop until the user answers the approval dialog, and is the only thing
 * the UI talks to. Requests are serialized, so a parallel batch of tool calls asks one
 * question at a time.
 *
 * Every branch fails closed: a request nobody answers — dialog dismissed, app closed,
 * non-interactive run — ends in a denial instead of letting the tool run.
 */
class ToolApprovalGate(private val timeout: Duration = 5.minutes) {
    /** A request plus the answer it will complete. */
    class Pending internal constructor(val request: ToolApprovalRequest) {
        internal val decision = CompletableDeferred<Boolean>()
    }

    private val mutex = Mutex()
    private var sequence = 0

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The action waiting for a decision, or null. Drives the approval dialog. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    suspend fun awaitApproval(
        toolId: String,
        toolName: String,
        reason: String,
        detail: String,
    ): Boolean = mutex.withLock {
        val pending =
            Pending(
                ToolApprovalRequest(
                    id = "tool-approval-${++sequence}",
                    toolId = toolId,
                    toolName = toolName,
                    reason = reason,
                    detail = detail,
                ),
            )
        _pending.value = pending
        try {
            // A cancellation while waiting (user pressed stop) propagates out of `await`
            // and is rethrown rather than read as a denial — the run is over anyway.
            withTimeoutOrNull(timeout) { pending.decision.await() } ?: false
        } finally {
            _pending.value = null
        }
    }

    fun approve(requestId: String) {
        decide(requestId, allowed = true)
    }

    fun deny(requestId: String) {
        decide(requestId, allowed = false)
    }

    /** Denies the pending request; used when the dialog is dismissed without an answer. */
    fun dismissPending() {
        _pending.value?.decision?.complete(false)
    }

    private fun decide(
        requestId: String,
        allowed: Boolean,
    ) {
        val pending = _pending.value ?: return
        if (pending.request.id != requestId) return
        pending.decision.complete(allowed)
    }
}

/**
 * Whether the run in progress may ask the user anything. Chat runs carry
 * `interactive = true`; scheduled, heartbeat and silent runs carry `false`, so a risky tool
 * fails fast there instead of blocking on a dialog nobody is watching.
 *
 * Absent from the context means not interactive: a new caller has to opt in, not out.
 */
class ToolInteractionElement(val interactive: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInteractionElement>
}

/** True when the calling coroutine belongs to an interactive chat run. */
suspend fun isInteractiveRun(): Boolean = coroutineContext[ToolInteractionElement]?.interactive == true
