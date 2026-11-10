package com.baystudio.droide.ui

internal fun explorerDeleteExplanation(path: String, directory: Boolean, safLinked: Boolean): String = when {
    !directory -> "This file will be deleted. This is not part of editor undo history."
    safLinked -> "Delete $path and all of its contents from the linked folder? Recovery data may be retained until synchronization finishes. This is not part of editor undo history."
    else -> "Remove $path and all of its contents from this project? Droide keeps recovery data, so storage will not be freed yet. This is not part of editor undo history."
}
