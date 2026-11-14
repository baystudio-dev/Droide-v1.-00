package com.baystudio.droide.ui

import java.util.concurrent.CancellationException

/** Transient form results belong to one request and provider/authentication selection. */
internal class ProviderFormRequestFence {
    internal class Ticket(val providerId: String, val authMethodId: String?)

    private var active: Ticket? = null

    fun begin(providerId: String, authMethodId: String?): Ticket =
        Ticket(providerId, authMethodId).also { active = it }

    fun invalidate() { active = null }

    fun isCurrent(ticket: Ticket, providerId: String?, authMethodId: String?): Boolean =
        active === ticket && ticket.providerId == providerId && ticket.authMethodId == authMethodId

    fun <T> currentResult(
        ticket: Ticket,
        providerId: String?,
        authMethodId: String?,
        result: Result<T>,
    ): Result<T>? {
        // A cancelled operation never turns into an error banner, even after navigation.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.takeIf { isCurrent(ticket, providerId, authMethodId) }
    }
}
