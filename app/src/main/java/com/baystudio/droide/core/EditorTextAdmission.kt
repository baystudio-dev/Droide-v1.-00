package com.baystudio.droide.core

internal object EditorTextAdmission {
    fun requireNoNul(text: CharSequence?) {
        if (text != null && text.indexOf('\u0000') >= 0) throw BinaryFileException()
    }
}

internal class BinaryFileException(relativePath: String? = null) : IllegalArgumentException(
    "Binary file cannot be displayed as text" + (relativePath?.let { ": $it" } ?: "") +
        ". Original data was preserved.",
)
