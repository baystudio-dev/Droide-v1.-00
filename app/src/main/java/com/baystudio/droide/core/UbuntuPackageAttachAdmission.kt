package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A known package-only transaction can keep its admitted guest available to new shells. */
internal class UbuntuPackageAttachAdmission {
    private class Admission(val identity: String, val probe: () -> String?, val owner: Job)
    private val active = AtomicReference<Admission?>()

    fun mayAttach(): Boolean {
        val admitted = active.get() ?: return false
        if (!admitted.owner.isActive) return false
        val unchanged = admitted.probe() == admitted.identity
        // Cancellation/end/replacement during the bounded identity check cannot admit a caller.
        return unchanged && admitted.owner.isActive && active.get() === admitted
    }

    /** Called only after runtime admission, while the existing guest mutation gate is owned. */
    suspend fun <T> duringPackageOperation(identity: () -> String?, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val currentIdentity = identity() ?: return block()
        val admitted = Admission(currentIdentity, identity, requireNotNull(currentCoroutineContext()[Job]))
        check(active.compareAndSet(null, admitted)) { "Ubuntu package admission is already owned" }
        try { return block() }
        finally { active.compareAndSet(admitted, null) }
    }
}
