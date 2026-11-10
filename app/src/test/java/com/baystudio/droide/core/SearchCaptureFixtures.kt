package com.baystudio.droide.core

/** Only finite, already-owned test values; production providers capture directly at their map. */
internal fun WorkspaceSearchCaptureRequest.captureFiniteSnapshots(
    values: Iterable<WorkspaceDocumentSnapshot>,
): WorkspaceSearchCapture {
    val result = ArrayList<WorkspaceDocumentSnapshot>()
    for (value in values) {
        beforeDocument()
        if (!value.dirty) continue
        beforeSnapshot(value.path, value.content)
        result += value
    }
    return WorkspaceSearchCapture(result)
}
