package com.baystudio.droide.core

/** Shared by every split and consolidation branch of one compaction transaction. */
internal class CompactionRequestBudget {
    private var requests = 0

    fun enter(depth: Int) {
        check(depth <= MAX_DEPTH && requests < MAX_REQUESTS) { FAILURE }
        requests++
    }

    fun requireReduction(before: String, after: String) {
        check(after.length < before.length) { FAILURE }
    }

    companion object {
        const val MAX_REQUESTS = 64
        const val MAX_DEPTH = 12
        private const val FAILURE = "The provider could not reduce this context within the bounded compaction budget. Existing conversation was preserved; choose another model or retry later"
    }
}
