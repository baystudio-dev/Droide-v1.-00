package com.baystudio.droide.core

import android.content.Context
import com.baystudio.droide.BuildConfig
import java.io.File

/** Opt-in is bound to the *exact* reviewed Canonical archive, not to an indefinite download right. */
object FdroidUbuntuConsent {
    private const val PREFERENCES = "droide_fdroid_ubuntu_consent_v1"
    private const val APPROVED_SHA256 = "approved_archive_sha256"

    fun requiresConsentForFreshInstall(context: Context): Boolean {
        if (!BuildConfig.FDROID_DISTRIBUTION) return false
        val installed = File(context.applicationContext.filesDir,
            "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")
        return !installed.exists() && !isApproved(context)
    }

    fun isApproved(context: Context): Boolean {
        if (!BuildConfig.FDROID_DISTRIBUTION) return true
        return context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(APPROVED_SHA256, null) == FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256
    }

    fun approveCanonicalArchive(context: Context) {
        check(BuildConfig.FDROID_DISTRIBUTION) { "This consent applies only to the F-Droid build" }
        check(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(APPROVED_SHA256, FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256).commit()) {
            "Unable to save Ubuntu download consent"
        }
    }

    fun requireFreshInstallAuthorization(context: Context) {
        check(isApproved(context)) {
            "Ubuntu download needs your explicit consent. Open the Linux terminal and review the F-Droid download notice."
        }
    }
}
