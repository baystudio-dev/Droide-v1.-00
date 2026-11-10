package com.baystudio.droide.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.baystudio.droide.core.TerminalLinks

/** Only an explicit tap opens a browser; the terminal and its pending stdin stay owned by the workspace. */
internal object TerminalLinkOpener {
    fun open(context: Context, url: String): Boolean {
        if (TerminalLinks.findAt(url, 0) != url) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            unavailable(context)
            false
        } catch (_: SecurityException) {
            unavailable(context)
            false
        }
    }

    private fun unavailable(context: Context) {
        // Do not put authentication URLs, state, or tokens in logs or error messages.
        Toast.makeText(context, "No app could open this link. You can copy it from the terminal.", Toast.LENGTH_LONG).show()
    }
}
