package com.baystudio.droide.core

/** Aggregate accounting retained by one compaction transaction, shared across every branch.
 * Request, depth, convergence and per-checkpoint bounds remain in CompactionRequestBudget
 * and SessionManager; this restores the donor's independent retained-exchange ceiling.
 */
internal class CompactionWorkBudget {
    private var retainedChars = 0L

    fun retainExchange(requestChars: Int, responseChars: Int) {
        require(requestChars >= 0 && responseChars >= 0) { "Exchange lengths must be nonnegative" }
        val next = retainedChars + requestChars.toLong() + responseChars.toLong()
        check(next <= MAX_EXCHANGE_CHARS) {
            "Compaction accounting budget exhausted; original context was preserved"
        }
        retainedChars = next
    }

    companion object {
        const val MAX_EXCHANGE_CHARS = 1_500_000L
    }
}
