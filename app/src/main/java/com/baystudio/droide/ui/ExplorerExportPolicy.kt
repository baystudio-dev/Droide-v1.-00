package com.baystudio.droide.ui

import android.content.Intent
import com.baystudio.droide.core.ExportFileNames
import com.baystudio.droide.core.ExportFormat
import com.baystudio.droide.core.ExportPreflightSnapshot
import com.baystudio.droide.core.ExportSourceAdmission
import com.baystudio.droide.core.ExportSourceTicket

/** Shared by dirty-buffer selection and Explorer scope presentation; root is never activeDirectory. */
internal fun exportPathInScope(path: String, relativePath: String): Boolean =
    relativePath.isEmpty() || path == relativePath || path.startsWith("$relativePath/")

internal fun exportScopeTitle(path: String, directory: Boolean): String = when {
    path.isEmpty() -> "Export entire workspace"
    directory -> "Export folder"
    else -> "Export file"
}

internal fun validExportName(name: String): Boolean = ExportFileNames.valid(name)

/** Keep editable names per format; a real source named backup.tar must keep its extension. */
internal data class ExportOutputNames(val original: String, val archive: String = "$original.tar") {
    fun selected(format: ExportFormat): String = if (format == ExportFormat.TAR) archive else original
    fun renamed(format: ExportFormat, name: String): ExportOutputNames =
        if (format == ExportFormat.TAR) copy(archive = name) else copy(original = name)
}

internal fun exportPickerIntent(format: ExportFormat, name: String, sourceName: String = name): Intent {
    require(validExportName(name)) { "Choose a valid export name" }
    val intent = if (format == ExportFormat.TREE) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    else Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType(if (format == ExportFormat.TAR) "application/x-tar" else ExportFileNames.mimeType(sourceName))
        .putExtra(Intent.EXTRA_TITLE, name)
    return intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** Production picker boundary: a blocked observation never invokes the destination launcher.
 * Successful preflight is not a capture reservation; admission is checked again after picking. */
internal suspend fun chooseExportDestinationIfReady(
    admission: ExportSourceAdmission,
    ticket: ExportSourceTicket,
    launch: () -> Unit,
): ExportPreflightSnapshot {
    val snapshot = admission.preflight(ticket)
    if (snapshot.canChooseDestination) launch()
    return snapshot
}
