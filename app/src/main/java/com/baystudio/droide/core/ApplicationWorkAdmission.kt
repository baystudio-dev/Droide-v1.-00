package com.baystudio.droide.core

import android.content.Context

/** Durable application-work fence. Only an explicit user launch may open a new epoch. */
internal object ApplicationWorkAdmission {
    const val ACTION_REQUEST_EXIT = "com.baystudio.droide.action.REQUEST_EXIT"
    const val EXTRA_EXIT_EPOCH = "droide_exit_epoch"
    private var owner: Any? = null
    private var state: ApplicationWorkAdmissionState? = null

    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        if (owner === app && state != null) return
        val preferences = app.getSharedPreferences("application_work_admission", Context.MODE_PRIVATE)
        val created = ApplicationWorkAdmissionState(
            read = {
                val epoch = preferences.getString("epoch", null)
                if (epoch == null) null else ApplicationWorkAdmissionState.Snapshot(epoch, preferences.getBoolean("open", false))
            },
            write = { value ->
                check(preferences.edit().putString("epoch", value.epoch).putBoolean("open", value.open).commit()) {
                    "Could not persist application work state; work remains stopped"
                }
            },
        )
        // Publish the fail-closed owner before reading potentially unreadable persisted state.
        owner = app
        state = created
        created.initialize()
    }

    val isOpen: Boolean @Synchronized get() = state?.isOpen == true
    val currentEpoch: String @Synchronized get() = requireState().currentEpoch
    @Synchronized fun requireOpen(): String = requireState().requireOpen()
    @Synchronized fun isCurrentOpen(epoch: String): Boolean = state?.isCurrentOpen(epoch) == true
    @Synchronized fun closeForExit(): String = requireState().closeForExit()
    @Synchronized fun reopenFromUser(): String = requireState().reopenFromUser()

    /** Short non-suspending admission/publication only; never perform blocking work here. */
    @Synchronized fun <T> withOpen(expectedEpoch: String? = null, block: () -> T): T {
        val epoch = requireOpen()
        check(expectedEpoch == null || expectedEpoch == epoch) { "Application work belongs to an expired session" }
        return block()
    }

    private fun requireState() = checkNotNull(state) { "Application work admission has not been initialized" }
}
