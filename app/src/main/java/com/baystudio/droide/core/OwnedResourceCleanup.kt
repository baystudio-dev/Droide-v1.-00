package com.baystudio.droide.core

/** Release every owned resource even when one cleanup fails; never hide the failure report. */
internal object OwnedResourceCleanup {
    fun run(vararg actions: () -> Unit): Result<Unit> {
        var failure: Throwable? = null
        for (action in actions) {
            try { action() }
            catch (error: Exception) { failure = combine(failure, error) }
            catch (error: LinkageError) { failure = combine(failure, error) }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    suspend fun runSuspend(vararg actions: suspend () -> Unit): Result<Unit> {
        var failure: Throwable? = null
        for (action in actions) {
            try { action() }
            catch (error: Exception) { failure = combine(failure, error) }
            catch (error: LinkageError) { failure = combine(failure, error) }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun combine(first: Throwable?, next: Throwable): Throwable =
        first?.also { if (it !== next) it.addSuppressed(next) } ?: next
}
