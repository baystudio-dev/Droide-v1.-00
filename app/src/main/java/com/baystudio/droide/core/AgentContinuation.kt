package com.baystudio.droide.core

internal enum class AgentContinuation(val description: String) {
    EMPTY("Open a conversation with an existing task first."),
    WORKING("This conversation is still working. Wait for it to finish or use Stop."),
    CHANGED("The conversation changed. Reopen its continuation details."),
    NEEDS_REVIEW("An earlier operation or saved session needs review. Check its files and processes before continuing."),
    READY("Continue from the saved conversation. Completed actions remain in its history."),
}

internal const val AGENT_CONTINUATION_PROMPT =
    "Continue the unfinished task in this conversation. Check recorded results and the current workspace first. " +
    "Do not repeat completed actions or retry an operation with an unknown outcome without checking its effects. " +
    "If the task is already complete, report that instead."
