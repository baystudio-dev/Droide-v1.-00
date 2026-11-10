package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Integer-only accounting cases; no provider, sockets, threads, files, or generated large strings. */
class CompactionWorkBudgetTest {
    @Test fun requestAndResponseLengthsShareTheDonorAggregateCeiling() {
        val budget = CompactionWorkBudget()
        budget.retainExchange(400_000, 300_000)
        budget.retainExchange(300_000, 500_000)
        budget.retainExchange(0, 0)
        assertEquals(1_500_000L, CompactionWorkBudget.MAX_EXCHANGE_CHARS)
        val failure = assertThrows(IllegalStateException::class.java) { budget.retainExchange(0, 1) }
        assertTrue(failure.message.orEmpty().contains("original context was preserved"))
    }

    @Test fun individuallyAdmissibleExchangesCannotAccumulatePastTheLimit() {
        val budget = CompactionWorkBudget()
        budget.retainExchange(600_000, 200_000)
        assertThrows(IllegalStateException::class.java) { budget.retainExchange(600_000, 200_000) }
    }

    @Test fun rejectedExchangeDoesNotConsumeOrResetTheRemainingAllowance() {
        val budget = CompactionWorkBudget()
        budget.retainExchange(1_499_990, 0)
        assertThrows(IllegalStateException::class.java) { budget.retainExchange(6, 5) }
        budget.retainExchange(5, 5)
        assertThrows(IllegalStateException::class.java) { budget.retainExchange(1, 0) }
    }

    @Test fun integerAdditionCannotWrapIntoAnAdmittedExchange() {
        assertThrows(IllegalStateException::class.java) {
            CompactionWorkBudget().retainExchange(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    @Test fun invalidLengthsCannotCreditTheBudget() {
        val budget = CompactionWorkBudget()
        budget.retainExchange(1_500_000, 0)
        assertThrows(IllegalArgumentException::class.java) { budget.retainExchange(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { budget.retainExchange(0, -1) }
        assertThrows(IllegalStateException::class.java) { budget.retainExchange(1, 0) }
    }

    @Test fun separateCompactionsHaveIndependentAccounting() {
        val exhausted = CompactionWorkBudget()
        exhausted.retainExchange(1_500_000, 0)
        CompactionWorkBudget().retainExchange(1_500_000, 0)
        assertThrows(IllegalStateException::class.java) { exhausted.retainExchange(0, 1) }
    }
}
