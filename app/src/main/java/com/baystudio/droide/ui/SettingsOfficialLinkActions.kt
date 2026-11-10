package com.baystudio.droide.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Settings owns one external-link failure/copy prompt; a newer click supersedes the older prompt. */
internal class SettingsOfficialLinkActions(
    private val scope: CoroutineScope,
    private val dispatch: (SettingsOfficialLink) -> String?,
    private val reportFailure: suspend (SettingsOfficialLink, String) -> Unit,
) {
    private var feedback: Job? = null
    private var closed = false

    fun close() {
        closed = true
        feedback?.cancel()
        feedback = null
    }

    fun open(link: SettingsOfficialLink) {
        if (closed || !scope.isActive) return
        feedback?.cancel()
        feedback = null
        val message = dispatch(link) ?: return
        feedback = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().ensureActive()
            reportFailure(link, message)
        }
    }
}
