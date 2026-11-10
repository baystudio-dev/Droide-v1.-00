package com.baystudio.droide.core

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import com.baystudio.droide.R

/** Resource-backed Icons can cross the notification parcel without decoding a bitmap on each
 * update. */
internal fun Notification.Builder.withDroideIcons(context: Context): Notification.Builder =
    setSmallIcon(R.drawable.ic_stat_droide)
        .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
