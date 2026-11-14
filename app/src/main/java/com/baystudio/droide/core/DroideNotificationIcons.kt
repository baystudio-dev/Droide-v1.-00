package com.baystudio.droide.core

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import com.baystudio.droide.R

/** Supply both standard notification icon slots from the installed app's resources.
 * The status bar needs a transparent monochrome symbol; the shade may display a full-color
 * large icon. Keep the latter explicit instead of relying on a system/OEM app-icon fallback.
 * Resource-backed Icons can cross the notification parcel without decoding a bitmap on each update.
 */
internal fun Notification.Builder.withDroideIcons(context: Context): Notification.Builder =
    setSmallIcon(R.drawable.ic_stat_droide)
        .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
