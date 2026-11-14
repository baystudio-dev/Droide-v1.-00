package com.baystudio.droide.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Called only by a user's Settings action. No fetch, resolver query, WebView, attachment or account data. */
internal object SettingsOfficialLinkLauncher {
    fun intentFor(link: SettingsOfficialLink): Intent {
        check(SettingsOfficialLinks.isAllowedTarget(link.url))
        return Intent(if (link.isEmail) Intent.ACTION_SENDTO else Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
            if (!link.isEmail) addCategory(Intent.CATEGORY_BROWSABLE)
            // Settings can be hosted by an Activity or a wrapped/application context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** A successful dispatch is not proof that the destination loaded or a message was sent. */
    fun open(context: Context, link: SettingsOfficialLink): String? = try {
        context.startActivity(intentFor(link))
        null
    } catch (_: ActivityNotFoundException) {
        link.unavailableMessage
    } catch (_: SecurityException) {
        link.unavailableMessage
    }
}
