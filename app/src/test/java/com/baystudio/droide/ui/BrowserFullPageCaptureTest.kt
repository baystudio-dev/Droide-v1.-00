package com.baystudio.droide.ui

import org.robolectric.RuntimeEnvironment

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import com.baystudio.droide.core.AgentBrowserCapture
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.BrowserCaptureException
import com.baystudio.droide.core.VisionSupport
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Android view + raster tests with controlled readiness/drawing, not a Chromium engine certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserFullPageCaptureTest {
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var activity: ActivityController<Activity>
    private lateinit var view: ControlledWebView
    private var leaseActive = true
    private var now = 0L
    private var allocated: Bitmap? = null
    private var blockCalls = 0

    private class ControlledWebView(context: Context) : WebView(context) {
        var page = "https://docs.example.test/page"
        var htmlHeight = 250
        var cachedScale: Double? = 1.0
        var captureBitmap: Bitmap? = null
        var captureCanvasDimensions: Pair<Int, Int>? = null
        var holdVisualRequest: Int? = null
        var requests = 0
        val pending = mutableListOf<Pair<Long, VisualStateCallback>>()
        val captureOffsets = mutableListOf<Int>()
        val scrollCalls = mutableListOf<Pair<Int, Int>>()
        var onCaptureDraw: (() -> Unit)? = null
        var failOriginalRestore = false
        var ignoreOriginalRestore = false

        override fun getUrl(): String = page
        override fun getContentHeight(): Int = htmlHeight
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun scrollTo(x: Int, y: Int) {
            if (failOriginalRestore && x == 7 && y == 20) throw IllegalStateException("controlled scroll restoration failure")
            if (ignoreOriginalRestore && x == 7 && y == 20) return
            val physicalHeight = (htmlHeight * (cachedScale ?: 1.0)).toInt()
            val clamped = y.coerceIn(0, (physicalHeight - height).coerceAtLeast(0))
            scrollCalls += x to clamped
            super.scrollTo(x, clamped)
        }
        override fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {
            requests++
            pending += requestId to callback
            if (requests != holdVisualRequest) Handler(Looper.getMainLooper()).post { callback.onComplete(requestId) }
        }
        override fun draw(canvas: Canvas) {
            val active = captureBitmap
            val dimensions = active?.let { it.width to it.height } ?: captureCanvasDimensions
            if (dimensions == null || canvas.width != dimensions.first || canvas.height != dimensions.second) {
                canvas.drawColor(Color.WHITE)
                return
            }
            captureOffsets += scrollY
            onCaptureDraw?.invoke()
            val paint = Paint()
            for (localY in 0 until height) {
                paint.color = rowColor(scrollY + localY)
                canvas.drawRect(0f, localY.toFloat(), width.toFloat(), localY + 1f, paint)
            }
        }
    }

    private data class Receipt(val width: Int, val height: Int, val captured: Int, val finalHeight: Long?,
        val tiles: Int, val complete: Boolean, val reasons: List<String>)

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        view = ControlledWebView(activity.get())
        val host = FrameLayout(activity.get())
        host.addView(view, FrameLayout.LayoutParams(120, 100))
        activity.get().setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        host.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 120, 100)
        view.forceLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 120, 100)
        // ShadowWebView does not perform Chromium layout or requestLayout from
        // setLayoutParams. Use real public View bounds, as in the lifecycle fixture.
        view.left = 0
        view.top = 0
        view.right = 120
        view.bottom = 100
        view.scrollTo(7, 20)
        assertTrue(view.isAttachedToWindow)
        assertEquals(120, view.width)
        assertEquals(100, view.height)
    }

    @After fun release() {
        caller.cancel()
        view.destroy()
        activity.pause().stop().destroy()
    }

    private fun capture(fullPage: Boolean = true,
        allocationFailure: Boolean = false,
        afterAllocation: (Bitmap) -> Unit = {},
        inspect: suspend (AgentBrowserCapture.Captured) -> Unit = {}): Deferred<Result<Receipt>> = caller.async {
        runCatching {
            AgentBrowserCapture.withCapture(view, fullPage, "https://docs.example.test/page",
                checkLease = { check(leaseActive) { "lease replaced" } },
                scaleOnMain = { view.cachedScale }, clock = { now },
                allocateBitmap = { width, height ->
                    assertNotEquals("Native raster allocation must not block the UI thread", Looper.getMainLooper().thread, Thread.currentThread())
                    if (allocationFailure) throw OutOfMemoryError("controlled allocation fault")
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocated = it
                        view.captureBitmap = it
                        afterAllocation(it)
                    }
                }) { result ->
                blockCalls++
                assertSame(allocated, result.bitmap)
                assertFalse(result.bitmap.isRecycled)
                inspect(result)
                Receipt(result.bitmap.width, result.bitmap.height, result.capturedSourceHeight,
                    result.finalSourceHeight, result.tilesDrawn, result.complete, result.incompleteReasons)
            }
        }
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(2)
        }
        assertTrue("Capture did not progress before the test deadline", condition())
    }

    private fun <T> completed(result: Deferred<T>): T {
        drainUntil { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun assertControlledMemoryFailure(failure: Throwable?, controlledMessage: String) {
        val chain = generateSequence(failure) { it.cause }.take(16).toList()
        val diagnostic = chain.joinToString(" -> ") { "${it.javaClass.simpleName}(${it.message})" }
        // Coroutine stack-trace recovery may copy BrowserCaptureException and use
        // the original exception as its cause. Inspect the retained terminal fault.
        println("Controlled capture memory fault: $diagnostic")
        assertTrue("Expected recoverable capture failure: $diagnostic", failure is BrowserCaptureException)
        assertTrue("Expected retained terminal memory fault: $diagnostic", chain.lastOrNull() is OutOfMemoryError)
        assertEquals("Expected the injected memory fault: $diagnostic", controlledMessage, chain.last().message)
        assertTrue("Unexpected exception wrapping: $diagnostic", chain.dropLast(1).all { it is BrowserCaptureException })
    }

    @Test fun stitchesBottomOverlapExactlyAndRestoresViewportBeforeEncoding() {
        val result = completed(capture { captured ->
            assertEquals(7, view.scrollX)
            assertEquals(20, view.scrollY)
            for (row in listOf(0, 99, 100, 199, 200, 249)) assertEquals(rowColor(row), captured.bitmap.getPixel(10, row))
            assertTrue(captured.bitmap.allocationByteCount <= AgentBrowserCapture.MAX_BITMAP_BYTES)
        }).getOrThrow()
        assertEquals(listOf(0, 100, 150), view.captureOffsets)
        assertEquals(3, view.requests)
        assertEquals(120, result.width)
        assertEquals(250, result.height)
        assertEquals(250, result.captured)
        assertTrue(result.complete)
        assertTrue(result.reasons.isEmpty())
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun ordinaryViewportCaptureDoesNotScrollOrWaitForTiledReadiness() {
        val originalCalls = view.scrollCalls.toList()
        val result = completed(capture(fullPage = false)).getOrThrow()
        assertEquals(originalCalls, view.scrollCalls)
        assertEquals(0, view.requests)
        assertEquals(listOf(20), view.captureOffsets)
        assertEquals(120, result.width)
        assertEquals(100, result.height)
        assertTrue(result.complete)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun cancellingDuringWorkerAllocationReleasesBitmapBeforeAnyViewDraw() {
        val allocating = CountDownLatch(1)
        val releaseAllocation = CountDownLatch(1)
        val operation = capture(afterAllocation = {
            allocating.countDown()
            check(releaseAllocation.await(3, TimeUnit.SECONDS)) { "Test did not release allocation worker" }
        })
        try {
            drainUntil { allocating.count == 0L }
            operation.cancel()
        } finally {
            releaseAllocation.countDown()
        }
        drainUntil { operation.isCompleted }
        assertTrue(operation.isCancelled)
        assertTrue(allocated!!.isRecycled)
        assertTrue(view.captureOffsets.isEmpty())
        assertEquals(0, blockCalls)
        assertEquals(7, view.scrollX)
        assertEquals(20, view.scrollY)
    }

    @Test fun mainQueueInputRunsBetweenTileDrawsBeforeTheNextScroll() {
        val observedScroll = mutableListOf<Int>()
        view.onCaptureDraw = {
            if (view.captureOffsets.size < 3) {
                Handler(Looper.getMainLooper()).post { observedScroll += view.scrollY }
            }
        }
        val result = completed(capture()).getOrThrow()
        assertEquals(listOf(0, 100), observedScroll)
        assertTrue(result.complete)
        assertEquals(20, view.scrollY)
    }

    @Test fun missingCachedScaleReturnsAnExplicitViewportPrefix() {
        view.cachedScale = null
        val result = completed(capture()).getOrThrow()
        assertFalse(result.complete)
        assertNull(result.finalHeight)
        assertEquals(100, result.captured)
        assertEquals(listOf("scale_unavailable"), result.reasons)
        assertEquals(20, view.scrollY)
    }

    @Test fun timeBudgetReturnsOnlyDrawnRowsWithoutAllocatingASecondBitmap() {
        view.onCaptureDraw = { now = AgentBrowserCapture.MAX_CAPTURE_MS + 1 }
        val result = completed(capture { captured ->
            assertEquals(100, captured.bitmap.height)
            assertEquals(rowColor(99), captured.bitmap.getPixel(10, 99))
            assertSame(allocated, captured.bitmap)
        }).getOrThrow()
        assertEquals(100, result.height)
        assertEquals(100, result.captured)
        assertFalse(result.complete)
        assertEquals(listOf("time_limit"), result.reasons)
        assertEquals(20, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun lazyHeightChangeReportsPartialAndKeepsCapturedPrefixPixels() {
        view.onCaptureDraw = { view.htmlHeight = 400 }
        val result = completed(capture { captured ->
            assertEquals(250L, captured.plan.sourceHeight)
            assertEquals(rowColor(99), captured.bitmap.getPixel(10, 99))
        }).getOrThrow()
        assertEquals(400L, result.finalHeight)
        assertEquals(100, result.height)
        assertFalse(result.complete)
        assertEquals(listOf("document_height_changed"), result.reasons)
        assertEquals(20, view.scrollY)
    }

    @Test fun cancellationRecyclesRasterRestoresScrollAndIgnoresLateVisualCallback() {
        view.holdVisualRequest = 2
        val operation = capture()
        drainUntil { view.requests == 2 }
        operation.cancel()
        drainUntil { operation.isCompleted }
        assertTrue(operation.isCancelled)
        assertEquals(7, view.scrollX)
        assertEquals(20, view.scrollY)
        assertTrue(allocated!!.isRecycled)
        val draws = view.captureOffsets.toList()
        val pending = view.pending.last()
        pending.second.onComplete(pending.first)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(draws, view.captureOffsets)
        assertEquals(0, blockCalls)
    }

    @Test fun replacedLeaseCannotEncodeOrRestoreIntoTheNewOwner() {
        view.onCaptureDraw = { leaseActive = false }
        val result = completed(capture())
        assertTrue(result.isFailure)
        assertEquals(0, blockCalls)
        assertEquals(0, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun navigationDuringDrawCannotReturnPixelsOrScrollTheReplacementPage() {
        view.onCaptureDraw = { view.page = "https://docs.example.test/replacement" }
        val failure = completed(capture()).exceptionOrNull()
        assertTrue(failure is BrowserCaptureException)
        assertEquals(0, blockCalls)
        assertEquals(0, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun zoomChangeDuringTheLastDrawIsRejected() {
        view.htmlHeight = 100
        view.onCaptureDraw = { view.cachedScale = 2.0 }
        val failure = completed(capture()).exceptionOrNull()
        assertTrue(failure is BrowserCaptureException)
        assertTrue(failure!!.message.orEmpty().contains("zoom changed"))
        assertEquals(0, blockCalls)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun allocationAndDrawMemoryFailuresBecomeRecoverableAfterCleanup() {
        val allocation = completed(capture(allocationFailure = true)).exceptionOrNull()
        assertControlledMemoryFailure(allocation, "controlled allocation fault")
        assertEquals(20, view.scrollY)
        view.onCaptureDraw = { throw OutOfMemoryError("controlled drawing fault") }
        val drawing = completed(capture()).exceptionOrNull()
        assertControlledMemoryFailure(drawing, "controlled drawing fault")
        assertEquals(20, view.scrollY)
        assertTrue(allocated!!.isRecycled)
        assertEquals(0, blockCalls)
    }

    @Test fun failedEncoderBlockReleasesItsBorrowedBitmapAndLeavesViewportRestored() {
        val failure = completed(capture { throw IllegalStateException("controlled encoding failure") }).exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(7, view.scrollX)
        assertEquals(20, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun failedViewportRestoreCannotCommitAnImageAndStillRecyclesTheRaster() {
        view.onCaptureDraw = { view.failOriginalRestore = true }
        val failure = completed(capture()).exceptionOrNull()
        assertTrue(failure is BrowserCaptureException)
        assertTrue(failure!!.message.orEmpty().contains("viewport could not be restored"))
        assertEquals(0, blockCalls)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun ignoredViewportRestoreNeverEncodesAndStillRecyclesTheRaster() {
        view.onCaptureDraw = { view.ignoreOriginalRestore = true }
        val failure = completed(capture()).exceptionOrNull()
        assertTrue(failure is BrowserCaptureException)
        assertTrue(failure!!.message.orEmpty().contains("viewport could not be restored"))
        assertEquals(0, blockCalls)
        assertEquals(150, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun clampedViewportRestoreAfterDocumentShrinkNeverEncodesOrReportsSuccess() {
        view.onCaptureDraw = { view.htmlHeight = 100 }
        val failure = completed(capture()).exceptionOrNull()
        assertTrue(failure is BrowserCaptureException)
        assertTrue(failure!!.message.orEmpty().contains("viewport could not be restored"))
        assertEquals(0, blockCalls)
        assertEquals(0, view.scrollY)
        assertTrue(allocated!!.isRecycled)
    }

    @Test fun controllerForwardsFullPageAndCommitsAnActualStructuredPngArtifact() {
        val directory = Files.createTempDirectory("browser-full-page-").toFile()
        val controller = AgentBrowserController(directory)
        var binding: AgentBrowserController.Binding? = null
        try {
            directory.resolve("index.html").writeText("<html><body>Controlled capture fixture</body></html>")
            view.page = controller.htmlPreviewUrl("index.html")
            controller.bindOpenRequest {}
            controller.configure(view)
            binding = controller.attach(view)
            view.webViewClient.onScaleChanged(view, 0f, 1f)
            view.captureCanvasDimensions = 120 to 250
            val operation = caller.async { runCatching { controller.execute("screenshot", fullPage = true, mediaOwnerId = "capture-session") } }
            val response = Json.parseToJsonElement(completed(operation).getOrThrow()).jsonObject
            assertEquals(true, response["success"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(true, response["full_page_requested"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(true, response["complete"]!!.jsonPrimitive.booleanOrNull)
            assertEquals("viewport", response["horizontal_coverage"]!!.jsonPrimitive.content)
            assertEquals(250, response["captured_source_height"]!!.jsonPrimitive.int)
            assertEquals(3, response["tiles"]!!.jsonPrimitive.int)
            val relative = response["path"]!!.jsonPrimitive.content
            assertTrue(relative.startsWith(".droide/chat-media/blobs/"))
            val store = com.baystudio.droide.core.ChatMediaStore(directory)
            val imageFile = requireNotNull(store.resolvePath(relative))
            assertFalse(com.baystudio.droide.core.PathSecurity.contains(directory, imageFile))
            assertEquals(relative, store.ownerRefs("capture-session").single().path)
            assertFalse(directory.resolve(".droide/browser").exists())
            assertTrue(java.io.File(store.storageDirectory, "scratch/browser").walkTopDown().none { it.extension == "png" })
            assertTrue(imageFile.isFile)
            assertTrue(imageFile.length() in 1..VisionSupport.MAX_IMAGE_BYTES)
            val image = BitmapFactory.decodeFile(imageFile.path)
            assertNotNull(image)
            try {
                assertEquals(120, image.width)
                assertEquals(250, image.height)
                assertEquals(rowColor(200), image.getPixel(10, 200))
            } finally { image.recycle() }
            assertEquals(listOf(0, 100, 150), view.captureOffsets)
            assertEquals(7, view.scrollX)
            assertEquals(20, view.scrollY)
        } finally {
            binding?.let { controller.detach(it, view, preserveState = false) }
            com.baystudio.droide.core.PathSecurity.deleteTreeNoFollow(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory))
            directory.deleteRecursively()
        }
    }

    @Test fun capturedMediaRemainsOutsideConversationUntilExplicitAuthorizedPublication() {
        val directory = Files.createTempDirectory("browser-capture-review-").toFile()
        val controller = AgentBrowserController(directory)
        var binding: AgentBrowserController.Binding? = null
        try {
            directory.resolve("index.html").writeText("<html><body>Review fixture</body></html>")
            view.page = controller.htmlPreviewUrl("index.html")
            controller.bindOpenRequest {}; controller.configure(view); binding = controller.attach(view)
            view.captureCanvasDimensions = 120 to 100
            val observation = completed(caller.async {
                controller.executeObserved("screenshot", mediaOwnerId = "capture-session")
            })
            val store = com.baystudio.droide.core.ChatMediaStore(directory)
            val ref = requireNotNull(observation.media)
            val stagingOwner = requireNotNull(observation.mediaOwnerId)
            assertNotEquals("capture-session", stagingOwner)
            assertTrue(store.ownerRefs("capture-session").isEmpty())
            assertEquals(listOf(ref), store.ownerRefs(stagingOwner))
            assertEquals(view.page, observation.sources.single().url)
            completed(caller.async { controller.retainObservationMedia(observation, "capture-session") })
            completed(caller.async { controller.discardObservation(observation) })
            assertTrue(store.ownerRefs(stagingOwner).isEmpty())
            assertEquals(listOf(ref), store.ownerRefs("capture-session"))
            assertNotNull(store.findByPath(ref.path))
        } finally {
            binding?.let { controller.detach(it, view, preserveState = false) }
            com.baystudio.droide.core.PathSecurity.deleteTreeNoFollow(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory))
            directory.deleteRecursively()
        }
    }

    @Test fun deniedOrCancelledPublicationReleasesBothStagingAndAnyNewConversationReference() {
        val directory = Files.createTempDirectory("browser-capture-denied-").toFile()
        val controller = AgentBrowserController(directory)
        var binding: AgentBrowserController.Binding? = null
        try {
            directory.resolve("index.html").writeText("<html><body>Denied fixture</body></html>")
            view.page = controller.htmlPreviewUrl("index.html")
            controller.bindOpenRequest {}; controller.configure(view); binding = controller.attach(view)
            view.captureCanvasDimensions = 120 to 100
            val observation = completed(caller.async {
                controller.executeObserved("screenshot", mediaOwnerId = "capture-session")
            })
            val store = com.baystudio.droide.core.ChatMediaStore(directory)
            val ref = requireNotNull(observation.media)
            completed(caller.async { controller.retainObservationMedia(observation, "capture-session") })
            completed(caller.async { controller.discardObservation(observation, "capture-session") })
            assertTrue(store.ownerRefs("capture-session").isEmpty())
            assertTrue(store.ownerRefs(requireNotNull(observation.mediaOwnerId)).isEmpty())
            assertNull(store.findByPath(ref.path))
        } finally {
            binding?.let { controller.detach(it, view, preserveState = false) }
            com.baystudio.droide.core.PathSecurity.deleteTreeNoFollow(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory))
            directory.deleteRecursively()
        }
    }

    @Test fun backgroundPauseCancelsCaptureAndRestoresTheRetainedDocumentScroll() {
        val directory = Files.createTempDirectory("browser-capture-pause-").toFile()
        val controller = AgentBrowserController(directory)
        var binding: AgentBrowserController.Binding? = null
        try {
            directory.resolve("index.html").writeText("<html><body>Pause fixture</body></html>")
            view.page = controller.htmlPreviewUrl("index.html")
            controller.bindOpenRequest {}
            controller.configure(view)
            binding = controller.attach(view)
            view.webViewClient.onScaleChanged(view, 0f, 1f)
            view.captureCanvasDimensions = 120 to 250
            view.onCaptureDraw = { controller.setApplicationVisible(false) }
            val operation = caller.async { runCatching { controller.execute("screenshot", fullPage = true, mediaOwnerId = "capture-session") } }
            assertTrue(completed(operation).isFailure)
            assertEquals(listOf(0), view.captureOffsets)
            assertEquals(7, view.scrollX)
            assertEquals(20, view.scrollY)
            assertFalse(directory.resolve(".droide/browser").exists())
            assertTrue(java.io.File(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory), "scratch/browser")
                .walkTopDown().none { it.extension == "png" })
            assertTrue(com.baystudio.droide.core.ChatMediaStore(directory).ownerRefs("capture-session").isEmpty())
            controller.setApplicationVisible(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Resuming must not replay cancelled capture", listOf(0), view.captureOffsets)
            assertEquals(20, view.scrollY)
        } finally {
            binding?.let { controller.detach(it, view, preserveState = false) }
            com.baystudio.droide.core.PathSecurity.deleteTreeNoFollow(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory))
            directory.deleteRecursively()
        }
    }

    @Test fun controllerRejectsSameUrlReloadAndDoesNotRestoreOrCommitIntoTheReplacementDocument() {
        val directory = Files.createTempDirectory("browser-capture-reload-").toFile()
        val controller = AgentBrowserController(directory)
        var binding: AgentBrowserController.Binding? = null
        try {
            directory.resolve("index.html").writeText("<html><body>Reload fixture</body></html>")
            view.page = controller.htmlPreviewUrl("index.html")
            controller.bindOpenRequest {}
            controller.configure(view)
            binding = controller.attach(view)
            view.webViewClient.onScaleChanged(view, 0f, 1f)
            view.captureCanvasDimensions = 120 to 250
            view.onCaptureDraw = { view.webViewClient.onPageStarted(view, view.page, null) }
            val operation = caller.async { runCatching { controller.execute("screenshot", fullPage = true, mediaOwnerId = "capture-session") } }
            assertTrue(completed(operation).isFailure)
            assertEquals(listOf(0), view.captureOffsets)
            assertEquals(0, view.scrollY)
            assertFalse(directory.resolve(".droide/browser").exists())
            assertTrue(java.io.File(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory), "scratch/browser")
                .walkTopDown().none { it.extension == "png" })
            assertTrue(com.baystudio.droide.core.ChatMediaStore(directory).ownerRefs("capture-session").isEmpty())
        } finally {
            binding?.let { controller.detach(it, view, preserveState = false) }
            com.baystudio.droide.core.PathSecurity.deleteTreeNoFollow(com.baystudio.droide.core.ChatMediaStoragePaths.directory(directory))
            directory.deleteRecursively()
        }
    }

    companion object {
        private fun rowColor(row: Int): Int = Color.rgb(row and 255, (row / 256) and 255, 67)
    }
}
