package com.baystudio.droide.core

import android.os.Build

internal class NativePtyUnavailableException(cause: Throwable) :
    IllegalStateException("Native PTY is unavailable: ${cause.message ?: cause.javaClass.simpleName}", cause) {
    // The existing launch log retains the exception. The UI needs a bounded classification, not raw
    // messages that may contain paths or command text.
    val commandModeReason: String = "Interactive terminal could not start (${cause.javaClass.simpleName.take(64).ifEmpty { "StartupError" }}). " +
        "Open a new Ubuntu tab to try again; this tab remains in command mode."
}

internal object NativePtyCompatibility {
    // This is an API check, not a promise that opening the PTY will succeed.

    fun prepare(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2
}
