package com.baystudio.droide.core

import android.webkit.MimeTypeMap
import java.util.Locale

/** Destination names never change the names stored inside an archive or exported tree. */
internal object ExportFileNames {
    fun valid(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }

    fun suggested(path: String, workspaceName: String): String {
        val source = path.substringAfterLast('/').ifEmpty { workspaceName }
        val leaf = source.map { if (it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r') '_' else it }.joinToString("").trim()
        return leaf.takeIf(::valid) ?: "droide-export"
    }

    fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "tar" -> "application/x-tar"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}
