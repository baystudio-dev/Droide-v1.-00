package com.baystudio.droide.core

import com.baystudio.droide.BuildConfig

/** Pilot rollout only. Never gates recovery, cancellation, history or retained-data cleanup. */
internal object ExportRolloutPolicy {
    val newExportsEnabled: Boolean get() = BuildConfig.EXPLORER_EXPORT_EXPERIMENTAL

    fun requireNewExportEnabled() {
        check(newExportsEnabled) {
            "New Explorer exports are disabled in this build pending Android acceptance; existing export recovery remains available."
        }
    }
}
