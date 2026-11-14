package com.baystudio.droide.core

import android.content.Context
import android.content.SharedPreferences
import com.baystudio.droide.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Approval belongs to the reviewed installation plan, never to agent-supplied flags. */
internal class ExternalToolDownloadConsent(
    private val preferences: SharedPreferences,
    private val enabled: Boolean,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("external_tool_download_consent_v1", Context.MODE_PRIVATE),
        BuildConfig.FDROID_DISTRIBUTION,
    )

    fun notice(plan: ExtensionInstallPlan): String? = if (enabled &&
        preferences.getString(plan.familyId, null) != fingerprint(plan)) NOTICE else null

    suspend fun acceptFromUser(plan: ExtensionInstallPlan) {
        if (!enabled) return
        val identity = fingerprint(plan)
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString(plan.familyId, identity).commit()) {
                "Unable to save download consent. No installation was started."
            }
        }
    }

    fun requireAccepted(plan: ExtensionInstallPlan) {
        check(notice(plan) == null) { "Review and approve this external download in Droide before installing it." }
    }

    private fun fingerprint(plan: ExtensionInstallPlan): String {
        val normalized = when (plan) {
            is ExtensionInstallPlan.Android -> plan.copy(licenseAccepted = false)
            is ExtensionInstallPlan.AndroidLocalComponent -> plan.copy(licenseAccepted = false)
            is ExtensionInstallPlan.AndroidLocalWorkstation -> plan.copy(licenseAccepted = false)
            is ExtensionInstallPlan.LocalVendor -> plan.copy(elpHealthPermit = null)
            else -> plan
        }
        return MessageDigest.getInstance("SHA-256").digest(("1\n" + normalized).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        const val NOTICE = "This installs executable code and its dependencies from external publishers, outside F-Droid’s review. " +
            "Choose Install to allow the downloads listed above, or Cancel to keep your current environment."
    }
}
