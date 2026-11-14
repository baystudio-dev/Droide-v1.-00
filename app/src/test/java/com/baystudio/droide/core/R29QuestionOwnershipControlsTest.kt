package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class R29QuestionOwnershipControlsTest {
    @Test(timeout = 5_000)
    fun cancellationDuringVisiblePublicationLeavesNoPendingQuestion() = runBlocking {
        withTimeout(2_000) {
            val manager = QuestionManager()
            val owner = Job(coroutineContext[Job])
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                manager.pending.filterNotNull().first()
                owner.cancel()
            }
            try {
                val answer = async(owner, start = CoroutineStart.UNDISPATCHED) {
                    manager.ask("Question", "Cancel as soon as visible", emptyList())
                }
                answer.join()
                observer.join()
                assertTrue(answer.isCancelled)
                assertNull(manager.pending.value)
            } finally {
                owner.cancelAndJoin()
                observer.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 5_000)
    fun currentAnswerSucceedsExactlyOnce() = runBlocking {
        withTimeout(2_000) {
            val manager = QuestionManager()
            val answer = async(start = CoroutineStart.UNDISPATCHED) { manager.ask("Question", "Choose", emptyList()) }
            try {
                val question = requireNotNull(manager.pending.value)
                assertTrue(manager.answer(question.id, "First answer"))
                assertFalse(manager.answer(question.id, "Second answer"))
                assertEquals("First answer", answer.await())
                assertNull(manager.pending.value)
            } finally { answer.cancelAndJoin() }
        }
    }

    @Test(timeout = 5_000)
    fun cancelledIdCannotResolveAReplacementQuestion() = runBlocking {
        withTimeout(2_000) {
            val manager = QuestionManager()
            val old = async(start = CoroutineStart.UNDISPATCHED) { manager.ask("Old", "Old question", emptyList()) }
            val oldId = requireNotNull(manager.pending.value).id
            old.cancelAndJoin()
            assertFalse(manager.answer(oldId, "Late answer"))
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { manager.ask("New", "New question", emptyList()) }
            try {
                val current = requireNotNull(manager.pending.value)
                assertFalse(manager.answer(oldId, "Still late"))
                assertEquals(current, manager.pending.value)
                assertFalse(replacement.isCompleted)
                assertTrue(manager.answer(current.id, "Current answer"))
                assertEquals("Current answer", replacement.await())
            } finally { replacement.cancelAndJoin() }
        }
    }
}
