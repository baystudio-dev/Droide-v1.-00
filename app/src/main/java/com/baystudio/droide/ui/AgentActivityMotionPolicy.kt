package com.baystudio.droide.ui

/** Decorative policy only. No event delivery, execution, persistence or scroll decisions live here. */
internal object AgentActivityMotion {
    const val EntranceMillis = 180
    const val StatusMillis = 120
    const val DisclosureMillis = 180
    const val InitialAlpha = .72f
    const val MaxRememberedEntrances = 1_024
}

/** Stream part IDs are local to a run (for example tool:1:0), never globally unique. */
internal fun agentActivityItemKey(runId: Long, partId: String): String = "stream:$runId:$partId"

/** Seed with the whole initial live snapshot, not only visible rows, so reopening/restoring a
 * pane never stages old activity. */
internal class AgentActivityEntrancePolicy(
    initialPartIds: Iterable<String>,
    private val capacity: Int = AgentActivityMotion.MaxRememberedEntrances,
) {
    private val seen = linkedSetOf<String>()

    init {
        require(capacity > 0)
        for (id in initialPartIds) {
            if (seen.size == capacity) break
            seen.add(id)
        }
    }

    fun claim(partId: String, enabled: Boolean): Boolean {
        if (seen.size == capacity || !seen.add(partId)) return false
        return enabled
    }

    internal val rememberedCount: Int get() = seen.size
}
