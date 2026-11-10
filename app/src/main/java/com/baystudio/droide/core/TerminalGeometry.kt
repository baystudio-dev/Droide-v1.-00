package com.baystudio.droide.core

/** Geometry only: no command, output, process token, environment or credentials. */
data class TerminalViewportGeometry(
    val widthPixels: Int,
    val heightPixels: Int,
    val cellWidthPixels: Float,
    val lineHeightPixels: Int,
    val expectedColumns: Int,
    val expectedRows: Int,
    val emulatorColumns: Int,
    val emulatorRows: Int,
)

/** Accepted means the existing ioctl succeeded; this is explicitly not kernel readback. */
data class TerminalResizeReceipt(
    val generation: Long,
    val requestId: Long,
    val requestedColumns: Int,
    val requestedRows: Int,
    val emulatorColumns: Int,
    val emulatorRows: Int,
    val acceptedColumns: Int,
    val acceptedRows: Int,
    val status: Int,
) {
    companion object {
        const val ACCEPTED = 1
        const val FAILED = 2
        const val UNAVAILABLE = 3
        const val CLOSED = 4
    }
    val label: String get() = when (status) {
        ACCEPTED -> "ioctl accepted (not readback)"
        FAILED -> "resize failed"
        UNAVAILABLE -> "native size unavailable"
        CLOSED -> "session closed"
        else -> "unknown resize result"
    }
}

data class TerminalGeometrySnapshot(
    val owner: Any? = null,
    val viewport: TerminalViewportGeometry? = null,
    val receipt: TerminalResizeReceipt? = null,
) {
    val warning: String? get() = when {
        receipt?.status == TerminalResizeReceipt.FAILED -> "Terminal size sync failed · details in Commands"
        viewport != null && (viewport.expectedColumns != viewport.emulatorColumns ||
            viewport.expectedRows != viewport.emulatorRows) -> "Terminal grid differs from viewport · details in Commands"
        else -> null
    }

    fun description(): String = buildString {
        append("Terminal geometry\n")
        viewport?.let {
            append("View: ${it.widthPixels}×${it.heightPixels}px; cell width ${it.cellWidthPixels}px; line height ${it.lineHeightPixels}px\n")
            append("Viewport fit: ${it.expectedColumns}×${it.expectedRows}; emulator: ${it.emulatorColumns}×${it.emulatorRows}\n")
        } ?: append("View not measured yet\n")
        receipt?.let {
            append("Requested: ${it.requestedColumns}×${it.requestedRows}; result: ${it.label}\n")
            append(if (it.acceptedColumns > 0 && it.acceptedRows > 0)
                "Last accepted PTY size: ${it.acceptedColumns}×${it.acceptedRows}\n"
                else "No accepted PTY size recorded\n")
        } ?: append("This runtime has not supplied a checked resize result\n")
        append("Kernel size readback is not available. A program may intentionally truncate its own rows.")
    }
}
