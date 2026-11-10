package com.baystudio.droide.core

/** Generation can be quiet while a model reasons. Activity must not consume a total-call budget. */
internal object AgentTimeoutPolicy {
    const val RESPONSE_IDLE_MS = 30L * 60L * 1_000L
    const val CONNECT_MS = 30_000L
    const val COMMAND_YIELD_MS = 5_000L
    const val BUILD_DEADLINE_MS = 0L
    const val CLI_CONTROL_MS = RESPONSE_IDLE_MS
    // Bootstrap/control needs a finite readiness deadline, distinct from a long model turn.
    const val ACP_STARTUP_MS = 90_000L
    const val ACP_CONFIGURATION_MS = 30_000L
    // User-approved tool work ends on completion or Stop unless metadata sets a deadline.
    const val PLUGIN_TOOL_MS = 0L
    const val MCP_TOOL_MS = RESPONSE_IDLE_MS
    const val MAX_PLUGIN_TOOL_MS = 86_400_000L
}
