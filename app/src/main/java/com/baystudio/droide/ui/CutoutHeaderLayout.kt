package com.baystudio.droide.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/** Window coordinates, read during placement so the first frame and inset animation use real geometry. */
internal fun interface HeaderCutoutSource { fun rectangles(): List<Rect> }
internal val LocalHeaderCutouts = staticCompositionLocalOf { HeaderCutoutSource { emptyList() } }
internal enum class HeaderPart(val priority: Int) { MORE(0), PROJECTS(1), AGENT(2), RUN(3), SETTINGS(4), MCP(5), TITLE(6) }
internal fun Modifier.headerPart(part: HeaderPart) = layoutId(part)

internal object HeaderCutoutGeometry {
    data class Span(val left: Int, val right: Int) { val width get() = right - left }

    fun spans(width: Int, originX: Float, top: Float, bottom: Float, cutouts: List<Rect>, padding: Int, clearance: Int): List<Span> {
        val left = padding.coerceAtMost(width)
        val right = (width - padding).coerceAtLeast(left)
        val blocked = cutouts.filter { it.top < bottom && it.bottom > top && it.width > 0 }
            .map { Span((floor(it.left - originX).toInt() - clearance).coerceIn(left, right),
                (ceil(it.right - originX).toInt() + clearance).coerceIn(left, right)) }
            .filter { it.width > 0 }.sortedBy { it.left }
        val result = mutableListOf<Span>()
        var x = left
        for (block in blocked) {
            if (x < block.left) result += Span(x, block.left)
            x = maxOf(x, block.right)
        }
        if (x < right) result += Span(x, right)
        return result
    }

    /** Logical start-to-end packing; empty/overflowed controls receive no placement or hit target. */
    fun pack(widths: List<Int>, spans: List<Span>, gap: Int): List<Int?>? {
        var slot = 0
        var x = spans.firstOrNull()?.left ?: 0
        return widths.map { width ->
            if (width == 0) null else {
                while (slot < spans.size && x + width > spans[slot].right) {
                    slot++
                    if (slot < spans.size) x = spans[slot].left
                }
                if (slot == spans.size) return null
                x.also { x += width + gap }
            }
        }
    }

    /** Keep the leading controls/title fixed and pack only the action suffix from the safe end. */
    fun alignActionSuffix(widths: List<Int>, positions: List<Int?>, spans: List<Span>, gap: Int, firstAction: Int): List<Int?> {
        if (firstAction !in widths.indices) return positions
        val end = spans.lastOrNull()?.right ?: return positions
        val start = (0 until firstAction).mapNotNull { index ->
            positions[index]?.let { it + widths[index] + gap }
        }.maxOrNull() ?: 0
        val reversedSpans = spans.map { Span(maxOf(it.left, start), it.right) }
            .filter { it.width > 0 }.asReversed().map { Span(end - it.right, end - it.left) }
        val reversed = pack(widths.drop(firstAction).asReversed(), reversedSpans, gap) ?: return positions
        return positions.take(firstAction) + reversed.asReversed().mapIndexed { index, x ->
            x?.let { end - it - widths[firstAction + index] }
        }
    }
}

/** Size from title content; place against current cutouts without a next-frame correction. */
@Composable
internal fun CutoutHeaderRow(endAlignActions: Boolean = false, content: @Composable () -> Unit) {
    val cutouts = LocalHeaderCutouts.current
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val children = subcompose(Unit, content)
        val titleIndex = children.indexOfFirst { it.layoutId == HeaderPart.TITLE }
        // Title lines already use maxLines=1. Their intrinsic height remains valid after
        // horizontal ellipsis, including custom typography and nonlinear font scaling.
        val titleHeight = children.getOrNull(titleIndex)?.maxIntrinsicHeight(width) ?: 0
        val desiredContentHeight = maxOf(DroideDimensions.PrimaryTouch.roundToPx(), titleHeight)
        val height = constraints.constrainHeight(maxOf(DroideDimensions.TopBar.roundToPx(), desiredContentHeight + 8.dp.roundToPx()))
        val controlHeight = minOf(height, desiredContentHeight)
        val padding = 4.dp.roundToPx()
        val gap = 2.dp.roundToPx()
        layout(width, height) {
            val origin = coordinates?.positionInWindow() ?: return@layout
            val y = (height - controlHeight) / 2
            val physicalSpans = HeaderCutoutGeometry.spans(width, origin.x, origin.y + y,
                origin.y + y + controlHeight, cutouts.rectangles(), padding, padding)
            val spans = if (layoutDirection == LayoutDirection.Rtl)
                physicalSpans.reversed().map { HeaderCutoutGeometry.Span(width - it.right, width - it.left) } else physicalSpans
            val loose = Constraints(maxWidth = width, maxHeight = controlHeight)
            val controls = children.mapIndexed { index, child -> if (index == titleIndex) null else child.measure(loose) }
            val widths = controls.map { it?.width ?: 0 }.toMutableList()
            val optional = children.indices.filter { it != titleIndex && children[it].layoutId != HeaderPart.MORE }
                .sortedByDescending { (children[it].layoutId as? HeaderPart)?.priority ?: 4 }
            for (index in optional) {
                if (HeaderCutoutGeometry.pack(widths, spans, gap) != null) break
                widths[index] = 0
            }
            // The root retains a physically unusable top strip if even More cannot fit beside a cutout.
            if (HeaderCutoutGeometry.pack(widths, spans, gap) == null) return@layout
            var titleSlotWidth = 0
            if (titleIndex >= 0) {
                var high = width
                while (titleSlotWidth < high) {
                    val candidate = titleSlotWidth + (high - titleSlotWidth + 1) / 2
                    widths[titleIndex] = candidate
                    if (HeaderCutoutGeometry.pack(widths, spans, gap) != null) titleSlotWidth = candidate else high = candidate - 1
                }
                widths[titleIndex] = titleSlotWidth
            }
            // The slot anchors trailing actions; its contents keep their natural width and align to start.
            val title = if (titleIndex >= 0 && titleSlotWidth > 0)
                children[titleIndex].measure(loose.copy(maxWidth = titleSlotWidth)) else null
            val packed = checkNotNull(HeaderCutoutGeometry.pack(widths, spans, gap))
            val firstAction = children.indexOfFirst { it.layoutId == HeaderPart.RUN }
            val positions = if (endAlignActions) {
                HeaderCutoutGeometry.alignActionSuffix(widths, packed, spans, gap, firstAction)
            } else packed
            positions.forEachIndexed { index, x ->
                if (x != null) {
                    val child = if (index == titleIndex) title else controls[index]
                    child?.placeRelative(x, (height - child.height) / 2)
                }
            }
        }
    }
}
