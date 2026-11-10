package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.Collections
import java.util.IdentityHashMap

/** Coroutine stack-trace recovery may copy a throwable and retain the original as its cause. */
internal fun assertOriginatingFailure(expected: Throwable, actual: Throwable?) {
    assertNotNull("The operation must still fail", actual)
    assertEquals(expected.javaClass, actual!!.javaClass)
    assertEquals(expected.message, actual.message)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = actual
    var originating = false
    repeat(64) {
        val failure = current ?: return@repeat
        if (!seen.add(failure)) {
            current = null
            return@repeat
        }
        if (failure === expected) originating = true
        current = failure.cause
    }
    assertTrue("The propagated failure must retain the original throwable", originating)
}
