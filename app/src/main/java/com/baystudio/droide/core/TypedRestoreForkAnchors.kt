package com.baystudio.droide.core

/** UI anchors only, never source-call authority. Actual fork still verifies private receipts. */
internal object TypedRestoreForkAnchors {
    fun validatedEnds(saved: List<AgentMessage>, original: TypedChatCheckpoint,
        reconciled: TypedChatCheckpoint): Set<Int> {
        require(original.target == reconciled.target)
        // Compare each entry once. Never repeatedly copy/validate a prefix for every UI row.
        // Equality here is metadata equality; actual forks still resolve private receipts.
        val common = ArrayList<TypedChatEntry>()
        val before = original.exact.iterator()
        val after = reconciled.exact.iterator()
        while (before.hasNext() && after.hasNext()) {
            val entry = before.next()
            if (entry != after.next()) break
            common.add(entry)
        }
        val completed = TypedChatForkBoundary.completedPrefixWidths(common)
        return saved.mapNotNullTo(mutableSetOf()) { it.forkHistoryEnd?.takeIf(completed::contains) }
    }

    fun restore(saved: List<AgentMessage>, loaded: List<AgentMessage>, validatedEnds: Set<Int>): List<AgentMessage> {
        val sameCount=saved.size==loaded.size
        return loaded.mapIndexed { index, message ->
            val prior=if(sameCount) saved[index] else null
            val end=prior?.forkHistoryEnd?.takeIf { it in validatedEnds &&
                prior.copy(forkHistoryEnd=message.forkHistoryEnd)==message }
            // Keep every normalized loaded field. Never content-search duplicate UI text.
            message.copy(forkHistoryEnd=end)
        }
    }
}
