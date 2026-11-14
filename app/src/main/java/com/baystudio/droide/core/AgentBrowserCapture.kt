package com.baystudio.droide.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.view.isVisible
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal class BrowserCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Application-owned vertical scroll capture at the existing viewport width. The
 * WebView is never resized, and one mutable ARGB bitmap is owned for the whole
 * operation. Fixed/sticky overlays can repeat; lazy loading, video and animation
 * cannot be treated as an immutable full-document snapshot.
 * Six seconds bounds asynchronous capture waits and cooperative tile work;
 * Android's synchronous draw cannot be forcibly interrupted. This does not
 * prevent an OS memory kill or certify real-engine performance.
 */
internal object AgentBrowserCapture {
    const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
    const val MAX_PIXELS = MAX_BITMAP_BYTES / 4
    const val MAX_OUTPUT_WIDTH = 1600
    const val MAX_OUTPUT_HEIGHT = 8192
    const val MAX_SOURCE_HEIGHT = 32768
    const val MAX_TILES = 24
    const val MAX_CAPTURE_MS = 6_000L
    private const val VISUAL_STATE_MS = 750L
    private const val RESTORE_MS = 1_000L
    private val visualRequestIds = AtomicLong()

    data class Geometry(
        val viewportWidth: Int,
        val viewportHeight: Int,
        val htmlContentHeight: Int,
        val scale: Double?,
        val scrollX: Int = 0,
        val scrollY: Int = 0,
    ) {
        /** Physical scrollable extent; unknown scale is never guessed from getScale(). */
        val sourceHeight: Long?
            get() {
                val zoom = validScale(scale) ?: return null
                if (htmlContentHeight <= 0) return null
                val measured = ceil(htmlContentHeight.toDouble() * zoom)
                if (!measured.isFinite() || measured <= 0 || measured >= Long.MAX_VALUE.toDouble()) return null
                return maxOf(viewportHeight.toLong(), measured.toLong())
            }
    }

    data class Plan(
        val fullPage: Boolean,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val sourceHeight: Long?,
        val captureLeft: Int,
        val startY: Int,
        val captureHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val limitReasons: List<String>,
    ) {
        val scaleX: Double get() = outputWidth.toDouble() / viewportWidth
        val scaleY: Double get() = outputHeight.toDouble() / captureHeight
        val tileCount: Int get() = ((captureHeight.toLong() + viewportHeight - 1) / viewportHeight).toInt()
        val complete: Boolean get() = !fullPage || sourceHeight != null && limitReasons.isEmpty()

        fun scrollYFor(coveredHeight: Int): Int {
            require(coveredHeight in 0 until captureHeight)
            if (!fullPage) return startY
            val bottomScroll = maxOf(0L, (sourceHeight ?: viewportHeight.toLong()) - viewportHeight)
            return min(coveredHeight.toLong(), bottomScroll).toInt()
        }
    }

    data class Captured(
        val bitmap: Bitmap,
        val plan: Plan,
        val capturedSourceHeight: Int,
        val finalSourceHeight: Long?,
        val tilesDrawn: Int,
        /** Full-page completion covers the observed vertical extent at viewport width only. */
        val complete: Boolean,
        val incompleteReasons: List<String>,
    )

    /** Pure allocation/coverage plan. Quality floor limits tall-page capture instead of producing an unreadably thin image. */
    fun plan(geometry: Geometry, fullPage: Boolean): Plan {
        val width = geometry.viewportWidth
        val height = geometry.viewportHeight
        require(width > 0 && height > 0) { "Browser viewport is not laid out" }
        val sourceHeight = geometry.sourceHeight
        val reasons = mutableListOf<String>()
        val capturedHeight: Int
        val rasterScale: Double
        if (!fullPage) {
            capturedHeight = height
            // Downsample large viewports, but never invent extra pixels on a small
            // mobile surface: 2x upscaling quadruples raster/encoder work.
            rasterScale = min(1600.0 / maxOf(width, height), sqrt(2_000_000.0 / (width.toDouble() * height))).coerceAtMost(1.0)
        } else {
            if (validScale(geometry.scale) == null) reasons += "scale_unavailable"
            else if (sourceHeight == null) reasons += "document_height_unavailable"
            val requested = sourceHeight ?: height.toLong()
            val widthScale = min(1.0, MAX_OUTPUT_WIDTH.toDouble() / width)
            val qualityScale = min(0.5, widthScale)
            val limits = listOf(
                "source_height_limit" to MAX_SOURCE_HEIGHT.toLong(),
                "tile_limit" to height.toLong() * MAX_TILES,
                "output_height_limit" to floor(MAX_OUTPUT_HEIGHT / qualityScale).toLong(),
                "pixel_limit" to floor(MAX_PIXELS / (width * qualityScale * qualityScale)).toLong(),
            )
            val maximum = limits.minOf { it.second }.coerceAtLeast(1)
            capturedHeight = min(requested, maximum).coerceAtLeast(1).toInt()
            reasons += limits.filter { requested > it.second }.map { it.first }
            rasterScale = min(widthScale, min(MAX_OUTPUT_HEIGHT.toDouble() / capturedHeight,
                sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * capturedHeight))))
        }
        val outputWidth = floor(width * rasterScale).toInt().coerceAtLeast(1)
        val outputHeight = floor(capturedHeight * rasterScale).toInt().coerceAtLeast(1)
        check(outputWidth.toLong() * outputHeight <= MAX_PIXELS) { "Browser capture raster exceeds its pixel budget" }
        return Plan(fullPage, width, height, sourceHeight,
            if (fullPage) 0 else geometry.scrollX, if (fullPage) 0 else geometry.scrollY,
            capturedHeight, outputWidth, outputHeight, reasons.distinct())
    }

    /**
     * The suspend block borrows the bitmap for encoding; it must not retain or
     * recycle it. The controller owns PNG/file cleanup and verifies its lease
     * again before committing output. All view callbacks run only on main.
     * checkRestoreIdentity may allow a paused operation, but must still reject
     * replacement views/documents. It is used only to restore the original scroll.
     * Cached WebViewClient.onScaleChanged data must belong to that same lease.
     */
    suspend fun <T> withCapture(
        view: WebView,
        fullPage: Boolean,
        sourceUrl: String,
        checkLease: () -> Unit,
        scaleOnMain: () -> Double?,
        checkRestoreIdentity: () -> Unit = checkLease,
        clock: () -> Long = SystemClock::elapsedRealtime,
        allocateBitmap: (Int, Int) -> Bitmap = { width, height -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) },
        block: suspend (Captured) -> T,
    ): T {
        var ownedBitmap: Bitmap? = null
        var initial: Geometry? = null
        var scrolled = false
        suspend fun restore() {
            val saved = initial ?: return
            if (!scrolled) return
            withContext(NonCancellable) {
                try {
                    withTimeout(RESTORE_MS) {
                        withContext(Dispatchers.Main.immediate) {
                            checkRestoreIdentity()
                            if (view.url == sourceUrl) {
                                view.scrollTo(saved.scrollX, saved.scrollY)
                                // scrollTo is void and may silently clamp or ignore the
                                // requested position. Do not commit an image until the
                                // actual viewport is back at its original coordinates.
                                scrolled = view.scrollX != saved.scrollX || view.scrollY != saved.scrollY
                            }
                        }
                    }
                } catch (_: Exception) {
                    // A replacement page/view owns its own scroll; never alter it.
                    // Cleanup timeout or a dead surface does not mask the original failure.
                }
            }
        }
        try {
            val captured = withTimeout(MAX_CAPTURE_MS) {
                val started = clock()
                fun remaining(): Long = MAX_CAPTURE_MS - (clock() - started).coerceAtLeast(0)
                val first = withContext(Dispatchers.Main.immediate) {
                    page(view, sourceUrl, checkLease)
                    geometry(view, scaleOnMain)
                }
                initial = first
                val plan = plan(first, fullPage)
                withContext(Dispatchers.Main.immediate) {
                    page(view, sourceUrl, checkLease)
                    geometryUnchanged(first, geometry(view, scaleOnMain), fullPage)
                }
                // Bitmap creation and the potentially 16 MiB fill do not touch a
                // View. Keep them off the UI thread. Publish ownership inside the
                // worker, so cancellation while returning from withContext still
                // reaches the outer finally and releases the native allocation.
                val bitmap = withContext(Dispatchers.Default) {
                    memory { allocateBitmap(plan.outputWidth, plan.outputHeight) }.also {
                        ownedBitmap = it
                        check(it.width == plan.outputWidth && it.height == plan.outputHeight && it.isMutable &&
                            it.config == Bitmap.Config.ARGB_8888 && it.allocationByteCount <= MAX_BITMAP_BYTES) {
                            "Browser capture allocation does not match its bounded plan"
                        }
                        if (fullPage) memory { it.eraseColor(Color.WHITE) }
                    }
                }
                val reasons = plan.limitReasons.toMutableList()
                var covered = 0
                var tiles = 0
                var finalHeight = first.sourceHeight
                while (covered < plan.captureHeight && tiles < MAX_TILES) {
                    currentCoroutineContext().ensureActive()
                    if (remaining() <= 0) { reasons += "time_limit"; break }
                    if (fullPage) {
                        withContext(Dispatchers.Main.immediate) {
                            page(view, sourceUrl, checkLease)
                            geometryUnchanged(first, geometry(view, scaleOnMain), true)
                            scrolled = true
                            view.scrollTo(plan.captureLeft, plan.scrollYFor(covered))
                        }
                        try {
                            visualState(view, sourceUrl, checkLease, min(VISUAL_STATE_MS, remaining().coerceAtLeast(1)))
                        } catch (_: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            reasons += if (remaining() <= 0) "time_limit" else "visual_state_timeout"
                            break
                        }
                    }
                    val frame = withContext(Dispatchers.Main.immediate) {
                        page(view, sourceUrl, checkLease)
                        val current = geometry(view, scaleOnMain)
                        geometryUnchanged(first, current, fullPage)
                        val relativeTop = current.scrollY.toLong() - plan.startY
                        val availableEnd = relativeTop + plan.viewportHeight
                        if (current.scrollX != plan.captureLeft) return@withContext Frame(covered, current.sourceHeight, "horizontal_scroll_changed")
                        if (relativeTop > covered || availableEnd <= covered) return@withContext Frame(covered, current.sourceHeight, "scroll_stalled")
                        val end = min(plan.captureHeight.toLong(), availableEnd).toInt()
                        memory {
                            val canvas = Canvas(bitmap)
                            canvas.scale(plan.scaleX.toFloat(), plan.scaleY.toFloat())
                            canvas.clipRect(0f, covered.toFloat(), plan.viewportWidth.toFloat(), end.toFloat())
                            canvas.translate(0f, relativeTop.toFloat())
                            view.draw(canvas)
                        }
                        page(view, sourceUrl, checkLease)
                        val after = geometry(view, scaleOnMain)
                        geometryUnchanged(first, after, fullPage)
                        Frame(end, after.sourceHeight, null)
                    }
                    finalHeight = frame.height
                    if (frame.reason != null) { reasons += frame.reason; break }
                    covered = frame.end
                    tiles++
                    if (fullPage && finalHeight != first.sourceHeight) { reasons += "document_height_changed"; break }
                    if (covered < plan.captureHeight && tiles < MAX_TILES) {
                        // A renderer may acknowledge a visual request immediately.
                        // Explicitly yield the main queue between tile draws so
                        // input, lifecycle and cancellation can run without a sleep.
                        withContext(Dispatchers.Main) { yield() }
                    }
                }
                if (covered <= 0) throw BrowserCaptureException("Browser renderer could not capture the requested range: ${reasons.lastOrNull() ?: "no_progress"}")
                if (covered < plan.captureHeight) {
                    val rasterHeight = ceil(covered * plan.scaleY).toInt().coerceIn(1, bitmap.height)
                    withContext(Dispatchers.Main.immediate) {
                        page(view, sourceUrl, checkLease)
                        // Shrink the logical height in the same allocation: no second raster.
                        memory { bitmap.reconfigure(bitmap.width, rasterHeight, Bitmap.Config.ARGB_8888) }
                    }
                }
                Captured(bitmap, plan, covered, finalHeight, tiles,
                    plan.complete && covered == plan.captureHeight && reasons.isEmpty(), reasons.distinct())
            }
            // Give the user their original viewport back before PNG encoding starts.
            restore()
            withTimeout(RESTORE_MS) {
                withContext(Dispatchers.Main.immediate) {
                    page(view, sourceUrl, checkLease)
                    if (scrolled) throw BrowserCaptureException("Browser viewport could not be restored after capture; retry the active page")
                }
            }
            return block(captured).also { currentCoroutineContext().ensureActive() }
        } catch (failure: TimeoutCancellationException) {
            // Only a timeout owned by this still-active operation becomes a tool
            // failure. Parent/user cancellation must retain its cancellation semantics.
            currentCoroutineContext().ensureActive()
            throw BrowserCaptureException("Browser capture timed out; retry a viewport capture", failure)
        } finally {
            try { restore() } finally { ownedBitmap?.let { if (!it.isRecycled) it.recycle() } }
        }
    }

    private data class Frame(val end: Int, val height: Long?, val reason: String?)

    private fun validScale(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0 }

    private fun geometry(view: WebView, scaleOnMain: () -> Double?): Geometry = Geometry(
        view.width, view.height, view.contentHeight, validScale(scaleOnMain()), view.scrollX, view.scrollY,
    )

    private fun geometryUnchanged(initial: Geometry, current: Geometry, fullPage: Boolean) {
        if (current.viewportWidth != initial.viewportWidth || current.viewportHeight != initial.viewportHeight) {
            throw BrowserCaptureException("Browser viewport changed during capture; retry the active page")
        }
        if (fullPage && initial.scale != null && current.scale != initial.scale) {
            throw BrowserCaptureException("Browser zoom changed during capture; retry the active page")
        }
    }

    private fun page(view: WebView, sourceUrl: String, checkLease: () -> Unit) {
        checkLease()
        if (view.url != sourceUrl) throw BrowserCaptureException("Browser page changed during capture; retry the active page")
    }

    private fun <T> memory(operation: () -> T): T = try { operation() }
        catch (failure: OutOfMemoryError) { throw BrowserCaptureException("Not enough memory for browser capture; retry a smaller viewport capture", failure) }

    private suspend fun visualState(view: WebView, sourceUrl: String, checkLease: () -> Unit, timeout: Long) = withTimeout(timeout) {
        withContext(Dispatchers.Main.immediate) {
            page(view, sourceUrl, checkLease)
            require(view.isAttachedToWindow && view.isVisible && view.isShown) {
                "Full-page capture requires the visible browser surface"
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                val request = visualRequestIds.incrementAndGet()
                view.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!continuation.isActive || requestId != request) return
                        try {
                            page(view, sourceUrl, checkLease)
                            continuation.resume(Unit)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                })
            }
        }
    }
}
