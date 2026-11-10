package com.baystudio.droide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private enum class SheetPart { HEADER, BODY, FOOTER }

/** Pin the footer only if a readable body fits; otherwise scroll the whole sheet. */

@Composable
internal fun AdaptiveSheetColumn(
    minimumBodyHeight: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    val sheetScroll = rememberScrollState()
    val bodyScroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        val viewportHeight = constraints.maxHeight
        SubcomposeLayout(Modifier.fillMaxWidth().verticalScroll(sheetScroll)) { constraints ->
            val width = constraints.maxWidth
            val loose = Constraints(minWidth = width, maxWidth = width)
            val title = subcompose(SheetPart.HEADER) { Box(Modifier.fillMaxWidth()) { header() } }
                .single().measure(loose)
            val actions = subcompose(SheetPart.FOOTER) { Box(Modifier.fillMaxWidth()) { footer() } }
                .single().measure(loose)
            val gap = spacing.roundToPx()
            val available = (viewportHeight - title.height - actions.height - gap * 2).coerceAtLeast(0)
            val pinFooter = viewportHeight != Constraints.Infinity && available >= minimumBodyHeight.roundToPx()
            val content = subcompose(SheetPart.BODY) {
                Box(Modifier.fillMaxWidth().then(if (pinFooter) Modifier.verticalScroll(bodyScroll) else Modifier)) { body() }
            }.single().measure(loose.copy(maxHeight = if (pinFooter) available else Constraints.Infinity))
            layout(width, title.height + content.height + actions.height + gap * 2) {
                title.placeRelative(0, 0)
                content.placeRelative(0, title.height + gap)
                actions.placeRelative(0, title.height + gap + content.height + gap)
            }
        }
    }
}
