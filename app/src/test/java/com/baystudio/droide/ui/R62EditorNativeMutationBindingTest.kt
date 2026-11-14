package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

/** Production binding exercised with synchronous boundary callbacks, never a fake Sora editor. */
class R62EditorNativeMutationBindingTest {
    private class Harness {
        val owner = Any()
        var current = true
        var captures = 0
        var replacements = 0
        var publications = 0
        var capture: () -> Unit = {}
        var replace: (String, String, () -> Unit) -> Unit = { _, _, publish -> publish() }
        val binding = EditorNativeMutationBinding(owner, { current }, {
            captures++
            capture()
        }, { before, after, publish ->
            replacements++
            replace(before, after, publish)
        })
        fun apply() = binding.apply("before", "after") { publications++ }
    }

    private fun failure(block: () -> Unit): IllegalStateException =
        assertThrows(IllegalStateException::class.java, block)

    @Test fun preservesExactOwnerAndCapturesSettledStateBeforeReturning() {
        val h = Harness()
        val order = mutableListOf<String>()
        h.capture = { order += "capture" }
        assertSame(h.owner, h.binding.owner)
        h.binding.requireCaptureAllowed()
        h.binding.prepare()
        order += "prepared"
        assertEquals(listOf("capture", "prepared"), order)
        assertEquals(1, h.captures)
        assertEquals(0, h.replacements)
        assertEquals(0, h.publications)
    }

    @Test fun staleOwnerRejectsCapturePrepareAndApplyBeforeCallbacks() {
        val h = Harness()
        h.current = false
        failure { h.binding.requireCaptureAllowed() }
        failure { h.binding.prepare() }
        failure { h.apply() }
        assertEquals(0, h.captures)
        assertEquals(0, h.replacements)
        assertEquals(0, h.publications)
    }

    @Test fun prepareRechecksOwnershipAfterCapture() {
        val h = Harness()
        h.capture = { h.current = false }
        failure { h.binding.prepare() }
        assertEquals(1, h.captures)
        assertEquals(0, h.replacements)
        assertEquals(0, h.publications)
    }

    @Test fun captureFailurePropagatesAndLaterPreparationCanRecover() {
        val h = Harness()
        val expected = IllegalStateException("capture failed")
        h.capture = { throw expected }
        assertSame(expected, failure { h.binding.prepare() })
        h.capture = {}
        h.binding.prepare()
        h.apply()
        assertEquals(2, h.captures)
        assertEquals(1, h.publications)
    }

    @Test fun applyUsesExactTextAndPublishesOnlyInsideReplacement() {
        val h = Harness()
        val order = mutableListOf<String>()
        h.replace = { before, after, publish ->
            assertEquals("before", before)
            assertEquals("after", after)
            order += "native replaced"
            assertEquals(0, h.publications)
            publish()
            assertEquals(1, h.publications)
            order += "replacement finished"
        }
        h.binding.apply("before", "after") { order += "published"; h.publications++ }
        order += "returned"
        assertEquals(listOf("native replaced", "published", "replacement finished", "returned"), order)
        assertEquals(0, h.captures)
        h.binding.requireCaptureAllowed()
    }

    @Test fun replacementBlocksReentrantCaptureAndPrepare() {
        val h = Harness()
        h.replace = { _, _, publish ->
            failure { h.binding.requireCaptureAllowed() }
            failure { h.binding.prepare() }
            assertEquals(0, h.captures)
            publish()
        }
        h.apply()
        h.binding.prepare()
        assertEquals(1, h.captures)
    }

    @Test fun replacementBlocksReentrantApplyWithoutInvokingAnotherReplacement() {
        val h = Harness()
        var entered = false
        h.replace = { _, _, publish ->
            if (!entered) {
                entered = true // Keeps this test finite even when the guard is deliberately removed.
                failure { h.apply() }
                assertEquals(1, h.replacements)
            }
            publish()
        }
        h.apply()
        assertEquals(1, h.replacements)
        assertEquals(1, h.publications)
    }

    @Test fun publicationBlocksReentrantCapturePrepareAndApply() {
        val h = Harness()
        h.binding.apply("before", "after") {
            failure { h.binding.requireCaptureAllowed() }
            failure { h.binding.prepare() }
            failure { h.apply() }
            h.publications++
        }
        assertEquals(0, h.captures)
        assertEquals(1, h.replacements)
        assertEquals(1, h.publications)
        h.binding.requireCaptureAllowed()
    }

    @Test fun replacementFailureBeforePublicationPropagatesAndUnlocks() {
        val h = Harness()
        val expected = IllegalStateException("native replacement failed")
        h.replace = { _, _, _ -> throw expected }
        assertSame(expected, failure { h.apply() })
        assertEquals(0, h.publications)
        h.binding.requireCaptureAllowed()
        h.replace = { _, _, publish -> publish() }
        h.apply()
        assertEquals(1, h.publications)
    }

    @Test fun returningWithoutPublicationFailsAndUnlocks() {
        val h = Harness()
        h.replace = { _, _, _ -> }
        failure { h.apply() }
        assertEquals(0, h.publications)
        h.binding.requireCaptureAllowed()
        h.replace = { _, _, publish -> publish() }
        h.apply()
        assertEquals(1, h.publications)
    }

    @Test fun ownershipLossBeforePublicationPreventsPublishingAndUnlocks() {
        val h = Harness()
        h.replace = { _, _, publish -> h.current = false; publish() }
        failure { h.apply() }
        assertEquals(0, h.publications)
        h.current = true
        h.binding.requireCaptureAllowed()
    }

    @Test fun ownershipLossAfterPublicationCannotReportSuccess() {
        val h = Harness()
        h.replace = { _, _, publish -> publish(); h.current = false }
        failure { h.apply() }
        assertEquals(1, h.publications)
        h.current = true
        h.binding.requireCaptureAllowed()
    }

    @Test fun ownershipLossWithinPublisherCannotReportSuccess() {
        val h = Harness()
        failure { h.binding.apply("before", "after") { h.publications++; h.current = false } }
        assertEquals(1, h.publications)
        h.current = true
        h.binding.requireCaptureAllowed()
    }

    @Test fun publicationFailurePropagatesUnchangedAndUnlocks() {
        val h = Harness()
        val expected = IllegalStateException("model publication failed")
        assertSame(expected, failure { h.binding.apply("before", "after") { throw expected } })
        h.binding.requireCaptureAllowed()
        h.apply()
        assertEquals(1, h.publications)
    }

    @Test fun postPublicationReplacementFailureIsNotReportedAsSuccess() {
        val h = Harness()
        val expected = IllegalStateException("native follow-up failed")
        h.replace = { _, _, publish -> publish(); throw expected }
        assertSame(expected, failure { h.apply() })
        assertEquals(1, h.publications)
        h.binding.requireCaptureAllowed()
    }

    @Test fun secondPublicationFailsBeforeInvokingPublisherAgain() {
        val h = Harness()
        h.replace = { _, _, publish -> publish(); publish() }
        failure { h.apply() }
        assertEquals(1, h.publications)
        h.binding.requireCaptureAllowed()
    }

    @Test fun eachNewApplyHasItsOwnPublicationAndFinallyClearsGuard() {
        val h = Harness()
        repeat(3) { h.apply(); h.binding.prepare() }
        assertEquals(3, h.replacements)
        assertEquals(3, h.publications)
        assertEquals(3, h.captures)
    }

    @Test fun oneBindingDoesNotLockAnIndependentOwner() {
        val first = Harness()
        val second = Harness()
        first.replace = { _, _, publish ->
            second.binding.prepare()
            second.apply()
            publish()
        }
        first.apply()
        assertEquals(1, first.publications)
        assertEquals(1, second.publications)
        assertEquals(1, second.captures)
    }

    @Test fun retainedCallbackCannotPublishAfterMissingPublicationFailure() {
        val h = Harness()
        var escaped: (() -> Unit)? = null
        h.replace = { _, _, publish -> escaped = publish }
        failure { h.apply() }
        failure { escaped!!() }
        assertEquals(0, h.publications)
        h.binding.requireCaptureAllowed()
    }

    @Test fun retainedCallbackCannotPublishAfterSuccessfulReturn() {
        val h = Harness()
        var escaped: (() -> Unit)? = null
        h.replace = { _, _, publish -> escaped = publish; publish() }
        h.apply()
        failure { escaped!!() }
        assertEquals(1, h.publications)
        h.binding.requireCaptureAllowed()
    }

    @Test fun earlierExpiredCallbackCannotPublishDuringLaterTransaction() {
        val h = Harness()
        var expired: (() -> Unit)? = null
        h.replace = { _, _, publish -> expired = publish }
        failure { h.apply() }
        h.replace = { _, _, publish ->
            failure { expired!!() }
            assertEquals(0, h.publications)
            publish()
        }
        h.apply()
        assertEquals(1, h.publications)
    }

    @Test fun recursivePublicationFailsBeforeInvokingPublisherTwice() {
        val h = Harness()
        var publication: (() -> Unit)? = null
        var calls = 0
        h.replace = { _, _, publish -> publication = publish; publish() }
        h.binding.apply("before", "after") {
            calls++
            if (calls == 1) failure { publication!!() } // Finite even if duplicate guard regresses.
        }
        assertEquals(1, calls)
        h.binding.requireCaptureAllowed()
    }

}
