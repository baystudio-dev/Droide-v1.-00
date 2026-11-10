package com.baystudio.droide.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.baystudio.droide.core.ChatMediaAccess
import com.baystudio.droide.core.ChatMediaRef
import java.io.File

object AgentChatMediaOpener {
    fun open(context: Context, workDir: File, ref: ChatMediaRef): String? = launch {
        val uri = ChatMediaAccess.uri(context.applicationContext, workDir, ref)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.mediaType)
            clipData = ClipData.newRawUri("Droide attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun share(context: Context, workDir: File, ref: ChatMediaRef): String? = launch {
        val uri = ChatMediaAccess.uri(context.applicationContext, workDir, ref)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ref.mediaType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Droide attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share attachment").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private inline fun launch(action: () -> Unit): String? = try {
        action()
        null
    } catch (_: IllegalArgumentException) {
        "Attachment is expired or unavailable; choose the file again."
    } catch (_: IllegalStateException) {
        "Attachment is expired or unavailable; choose the file again."
    } catch (_: android.content.ActivityNotFoundException) {
        "No app can open this attachment. Use Share or install an app that supports this file."
    } catch (_: Exception) {
        "This attachment could not be opened. Try Share or choose the file again."
    }
}
