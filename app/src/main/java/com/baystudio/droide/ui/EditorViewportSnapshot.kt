package com.baystudio.droide.ui

internal const val DefaultEditorTextSizeSp = 13f

internal data class EditorViewportSnapshot(
    val offsetX: Int,
    val offsetY: Int,
    val textSizeSp: Float,
    // Only a zoom reset needs a logical anchor while Sora rebuilds wrapped rows.
    val zoomAnchor: EditorZoomAnchor? = null,
)

internal data class EditorZoomAnchor(
    val line: Int,
    val column: Int,
    val rowFraction: Float,
    val horizontalEm: Float,
)
