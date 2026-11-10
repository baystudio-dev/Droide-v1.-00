package com.baystudio.droide.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Join provider fractions only after this request owns the installer's download phase. */
internal class InstallerProviderProgressRelay(
    private val report: (PackageProgressSnapshot, Float?) -> Unit,
) {
    private var current: PackageProgressSnapshot? = null
    private var fraction: Float? = null

    @Synchronized fun operation(progress: PackageProgressSnapshot) {
        if (current?.stage != "downloading" || progress.stage != "downloading") fraction = null
        current = progress
        report(progress, fraction)
    }

    @Synchronized fun provider(value: Float?) {
        val progress = current?.takeIf { it.stage == "downloading" } ?: return
        fraction = value
        report(progress, fraction)
    }
}

/** Request-scoped observers are children of the existing installation job, never of a panel. */
internal suspend fun <T> withInstallerProgressReporting(
    fractions: kotlinx.coroutines.flow.Flow<Float?>?,
    report: (PackageProgressSnapshot, Float?) -> Unit,
    operation: suspend () -> T,
): T = kotlinx.coroutines.coroutineScope {
    val relay = InstallerProviderProgressRelay(report)

    val provider = fractions?.let { values ->
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            values.collect { relay.provider(it) }
        }
    }
    try {
        kotlinx.coroutines.withContext(PackageProgressObserver(relay::operation)) { operation() }
    } finally {
        // coroutineScope waits for this observer to finish; it must never keep the install open.
        provider?.cancel()
    }
}
