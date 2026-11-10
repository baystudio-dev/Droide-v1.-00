package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** Local saved project only. Empty path means the entire canonical project root. */
data class ExportScope(val relativePath: String = "") {
    init { require(relativePath.isEmpty() || (!relativePath.startsWith('/') && relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it })) }
}
enum class ExportFormat { RAW_FILE, TREE, TAR }
enum class ExportPhase { CAPTURING, WRITING, VERIFYING, COMPLETED_VERIFIED }
data class ExportProgress(val phase: ExportPhase, val entries: Long, val bytes: Long)
data class ExportControl(val checkCancelled: () -> Unit = {}, val onProgress: (ExportProgress) -> Unit = {})
/** Implemented only by admission; validate checks owner, epoch, root and cooperative writer state. */
interface ExportCapturePermit {
    val root: File
    val scope: ExportScope
    val stabilityAssumptions: List<String>
    fun validate()
}
/** Caller owns new-output creation, URI ownership journal and exact names/unique identities. */
interface ExportDestination {
    fun createDirectory(relativePath: String)
    fun createFile(relativePath: String): ExportOutput
}
/** Each returned stream is owned and closed by the engine. Reopen must return the written object. */
interface ExportOutput {
    fun openWrite(): OutputStream
    fun openRead(): InputStream
}
data class ExportVerification(val entries: Long, val bytes: Long, val stabilityAssumptions: List<String>)
class ExportFailure(val category: String, val relativePath: String = "", cause: Throwable? = null) :
    IOException("$category${if (relativePath.isEmpty()) "" else ": $relativePath"}", cause)
