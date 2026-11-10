package com.baystudio.droide.core

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
