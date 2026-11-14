package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

 
class AgentHookLifecycleBridge(
    private val hooks: HookManager,
    private val sessionId: () -> String,
    private val mode: () -> AgentMode,
) {
    private fun context(owner: HookInvocationAuthority?) = HookLifecycleContext(
        sessionId = owner?.provenance?.sessionId ?: sessionId(),
        mode = (owner?.mode ?: mode()).name.lowercase(),
    )

    private suspend fun checkCurrent(owner: HookInvocationAuthority?, beforeDispatch: (suspend () -> Unit)?) {
        owner?.requireCurrent()
        beforeDispatch?.invoke()
        owner?.requireCurrent() // The composed browser check may itself suspend.
    }

    private suspend fun dispatch(event: HookLifecycleEvent, context: HookLifecycleContext,
        owner: HookInvocationAuthority?, beforeDispatch: (suspend () -> Unit)? = null): HookDispatchResult {
        checkCurrent(owner, beforeDispatch)
        return hooks.dispatch(event, context,
            beforeDispatch = { checkCurrent(owner, beforeDispatch) },
            provenance = owner?.provenance, permissionScope = owner?.permissionScope,
            isStillCurrent = owner?.isStillCurrent, ownerAdmission = owner?.approvalAdmission,
        ).also { checkCurrent(owner, beforeDispatch) }
    }

    suspend fun sessionStart(trigger: String, owner: HookInvocationAuthority? = null) = dispatch(
        HookLifecycleEvent.SESSION_START, context(owner).copy(trigger = trigger), owner,
    )

    suspend fun userPrompt(prompt: String, owner: HookInvocationAuthority? = null): HookDispatchResult = dispatch(
        HookLifecycleEvent.USER_PROMPT_SUBMIT, context(owner).copy(prompt = prompt), owner,
    )

    suspend fun beforePrompt(creatingSession: Boolean, resumeTrigger: String?, prompt: String,
        owner: HookInvocationAuthority? = null): HookDispatchResult {
        val captured = context(owner)
        val trigger = if (creatingSession) "startup" else resumeTrigger
        val start = if (!trigger.isNullOrBlank())
            dispatch(HookLifecycleEvent.SESSION_START, captured.copy(trigger = trigger), owner)
        else HookDispatchResult()
        val submitted = dispatch(HookLifecycleEvent.USER_PROMPT_SUBMIT, captured.copy(prompt = prompt), owner)
        val context = HookLifecycleProtocol.boundedText(
            sequenceOf(start.additionalContext, submitted.additionalContext)
                .filter { it.isNotBlank() }.joinToString("\n"), HookLifecycleProtocol.MAX_CONTEXT_CHARS)
        return submitted.copy(additionalContext = context)
    }

    fun requiresSerializedTool(tool: String): Boolean = hooks.hasToolLifecycleHandlers(tool)

    suspend fun tool(tool: String, input: String, beforeDispatch: (suspend () -> Unit)? = null,
        owner: HookInvocationAuthority? = null, execute: suspend () -> String): String {
        val captured = context(owner).copy(toolName = tool, toolInput = input)
        return toolCaptured(tool, captured, beforeDispatch, owner, execute)
    }

    suspend fun toolSource(tool: String, input: HookToolInputSource,
        beforeDispatch: (suspend () -> Unit)? = null, owner: HookInvocationAuthority? = null,
        execute: suspend () -> String): String = toolCaptured(
            tool, context(owner).copy(toolName = tool, streamedToolInput = input), beforeDispatch, owner, execute,
        )

    private suspend fun toolCaptured(tool: String, captured: HookLifecycleContext,
        beforeDispatch: (suspend () -> Unit)?, owner: HookInvocationAuthority?,
        execute: suspend () -> String): String {
        val before = dispatch(HookLifecycleEvent.PRE_TOOL_USE, captured, owner, beforeDispatch)
        if (before.blocked) return "DENIED (hook): ${before.reason.ifBlank { "PreToolUse blocked $tool" }}"
        return try {
            checkCurrent(owner, beforeDispatch)
            val result = execute()
            val after = dispatch(HookLifecycleEvent.POST_TOOL_USE, captured.copy(toolOutput = result), owner, beforeDispatch)
            decorate(tool, result, before, after)
        } catch (t: Throwable) {
            if (t is CancellationException || t is BrowserPublicationRevokedException || t !is Exception) throw t
            try {
                dispatch(HookLifecycleEvent.POST_TOOL_USE_FAILURE,
                    captured.copy(error = t.message ?: t::class.java.simpleName), owner, beforeDispatch)
            } catch (reportingFailure: Throwable) {
                if (reportingFailure !== t) t.addSuppressed(reportingFailure)
            }
            throw t
        }
    }

    suspend fun compact(trigger: String, owner: HookInvocationAuthority? = null,
        action: suspend () -> Boolean): HookedCompaction {
        val captured = context(owner).copy(trigger = trigger)
        val before = dispatch(HookLifecycleEvent.PRE_COMPACT, captured, owner)
        if (before.blocked) return HookedCompaction(false, before.reason.ifBlank { "PreCompact hook blocked compaction" })
        checkCurrent(owner, null)
        val changed = action()
        checkCurrent(owner, null)
        if (changed) dispatch(HookLifecycleEvent.POST_COMPACT, captured, owner)
        return HookedCompaction(changed)
    }

    suspend fun stop(owner: HookInvocationAuthority? = null): HookDispatchResult =
        dispatch(HookLifecycleEvent.STOP, context(owner), owner)

    private fun decorate(tool: String, result: String, vararg hooks: HookDispatchResult): String {
        // Raw hook stdout/logs are diagnostics and must not become an implicit prompt-injection channel.

        val combinedContext = hooks.asSequence().map { it.additionalContext }.filter { it.isNotBlank() }.joinToString("\n")
        if (AgentCommandSessions.usesAgentEnvelope(tool)) {
            return AgentCommandSessions.appendHookContext(result, combinedContext)
        }
        val context = HookLifecycleProtocol.boundedText(combinedContext, HookLifecycleProtocol.MAX_CONTEXT_CHARS)
        // Result budgets belong to the producer. A lifecycle decorator must not
        // silently cut its payload (or JSON envelope) a second time.
        if (context.isBlank()) return result
        return buildString {
            append(result)
            append("\n[hook context]\n").append(context)
        }
    }
}

data class HookedCompaction(val changed: Boolean, val blockedReason: String = "")

/** Immutable authority for one lifecycle invocation; no storage or process-start guarantee. */
class HookInvocationAuthority(
    val mode: AgentMode,
    val provenance: AgentRequestProvenance,
    val permissionScope: String,
    val isStillCurrent: () -> Boolean,
    val approvalAdmission: ApprovalOwnerAdmission? = null,
) {
    fun requireCurrent() {
        if (!isStillCurrent()) throw CancellationException("Agent hook owner changed")
    }
}
