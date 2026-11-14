package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** R29 candidate API adaptation only; original baseline assertions are unchanged. */
class R29QuestionOwnershipBaselineTest {
    @Test(timeout = 5_000)
    fun staleQuestionUiCallbackMustNotAnswerReplacementQuestion() = runBlocking {
        withTimeout(2_000) {
            val manager = QuestionManager()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                manager.ask("Old", "Choose for the old session", listOf("Old choice"))
            }
            val oldQuestion = requireNotNull(manager.pending.value)
            // The UI captures the question identity together with its displayed option.
            val oldUiCallback = { manager.answer(oldQuestion.id, oldQuestion.options.single()) }
            old.cancelAndJoin()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                manager.ask("New", "Choose for the replacement session", listOf("New choice"))
            }
            try {
                val current = requireNotNull(manager.pending.value)
                assertNotEquals(oldQuestion.id, current.id)
                oldUiCallback()
                assertEquals("A stale question callback must leave the new question pending", current, manager.pending.value)
                assertFalse("The replacement must not receive the old answer", replacement.isCompleted)
                manager.answer(current.id, "New choice")
                assertEquals("New choice", replacement.await())
            } finally {
                replacement.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 5_000)
    fun visibleQuestionMustAlreadyOwnItsAnswerContinuation() = runBlocking {
        withTimeout(2_000) {
            val manager = QuestionManager()
            // Unconfined collection delivers the first visible question synchronously.
            // This exposes publication-before-registration without threads or timing loops.
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                val question = manager.pending.filterNotNull().first()
                manager.answer(question.id, "Immediate answer")
            }
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                manager.ask("Question", "Can this answer arrive immediately?", emptyList())
            }
            try {
                observer.join()
                assertTrue("Publishing a question must not expose an unanswerable continuation", answer.isCompleted)
                assertEquals("Immediate answer", answer.await())
                assertNull(manager.pending.value)
            } finally {
                answer.cancelAndJoin()
                observer.cancelAndJoin()
            }
        }
    }
}
