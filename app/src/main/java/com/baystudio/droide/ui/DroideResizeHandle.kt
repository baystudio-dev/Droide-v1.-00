package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class DroideResizeAxis { Horizontal, Vertical }

/** Stable pointer coroutine: resizing must not cancel its own gesture on every recomposition. */
@Composable
internal fun Modifier.droideResizeHandle(
    axis: DroideResizeAxis,
    value: Dp,
    min: Dp,
    max: Dp,
    description: String,
    onChange: (Dp) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val currentValue by rememberUpdatedState(value)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    val currentOnChange by rememberUpdatedState(onChange)
    fun setSize(next: Dp) = currentOnChange(next.coerceIn(currentMin, currentMax.coerceAtLeast(currentMin)))
    return semantics {
        contentDescription = description
        stateDescription = "${value.value.toInt()} dp"
        progressBarRangeInfo = ProgressBarRangeInfo(value.value, min.value..max.coerceAtLeast(min).value)
        setProgress { next ->
            if (next.isFinite()) { setSize(next.dp); true } else false
        }
    }.onKeyEvent { event ->
        val increase = if (axis == DroideResizeAxis.Horizontal) Key.DirectionLeft else Key.DirectionUp
        val decrease = if (axis == DroideResizeAxis.Horizontal) Key.DirectionRight else Key.DirectionDown
        if (event.type == KeyEventType.KeyDown && (event.key == increase || event.key == decrease)) {
            setSize(currentValue + if (event.key == increase) 16.dp else (-16).dp)
            true
        } else false
    }.focusable().pointerInput(axis, density) {
        var dragValue = currentValue
        fun resize(deltaPx: Float) {
            val delta = with(density) { deltaPx.toDp() }
            dragValue = (dragValue - delta).coerceIn(currentMin, currentMax.coerceAtLeast(currentMin))
            currentOnChange(dragValue)
        }
        when (axis) {
            DroideResizeAxis.Horizontal -> detectHorizontalDragGestures(
                onDragStart = { dragValue = currentValue },
            ) { change, amount -> change.consume(); resize(amount) }
            DroideResizeAxis.Vertical -> detectVerticalDragGestures(
                onDragStart = { dragValue = currentValue },
            ) { change, amount -> change.consume(); resize(amount) }
        }
    }
}
