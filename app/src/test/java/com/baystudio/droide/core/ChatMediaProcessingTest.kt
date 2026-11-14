package com.baystudio.droide.core

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [BoundedRetrieverShadow::class, HostImageFileSourceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMediaProcessingTest {
    private lateinit var work: File
    @Before fun setup() { work = Files.createTempDirectory("media-processing-").toFile(); BoundedRetrieverShadow.reset() }
    @After fun cleanup() { work.deleteRecursively() }

    @Test fun downsampleUsesPlatformExifAndReusesOwnedDerivative() = runBlocking {
        val store = ChatMediaStore(work)
        val source = jpegWithOrientation(2400, 1200, 6)
        val ref = store.import("session", "photo.jpg", "image/jpeg", ByteArrayInputStream(source))
        val first = ChatMediaProcessing.prepareModelImages(work, listOf(ref), "session")
        assertEquals(1, first.imagePaths.size)
        val preview = store.resolvePath(first.imagePaths.single())!!
        val decoded = BitmapFactory.decodeFile(preview.absolutePath)
        try {
            assertEquals(640, decoded.width)
            assertEquals(1280, decoded.height)
            assertTrue(preview.length() <= ChatMediaStore.MAX_DERIVATIVE_BYTES)
        } finally { decoded.recycle() }
        val modified = preview.lastModified()
        val second = ChatMediaProcessing.prepareModelImages(work, listOf(ref), "session")
        assertEquals(first, second)
        assertEquals(modified, preview.lastModified())
        assertArrayEquals(source, store.file(ref)!!.readBytes())
    }

    @Test fun emptyTextPromptDoesNotCreateMediaStorageOrStartDecoder() = runBlocking {
        assertEquals(PreparedMediaInput(emptyList(), ""), ChatMediaProcessing.prepareModelImages(work, emptyList(), "session"))
        assertFalse(File(work, ".droide").exists())
        assertEquals(0, BoundedRetrieverShadow.releases)
    }

    @Test fun viewImageDeliversPixelsForLargeOwnedOriginalThroughBoundedCachedPreview() = runBlocking {
        val store = ChatMediaStore(work)
        val source = jpegWithOrientation(100, 50, 1).copyOf((VisionSupport.MAX_IMAGE_BYTES + 1).toInt())
        val ref = store.import("session", "generated.jpg", "image/jpeg", ByteArrayInputStream(source))
        val base = agentWorkflowContext(work)
        base.perms.allowForSession("read", ref.path)
        val delivered = mutableListOf<AgentImageRef>()
        val context = AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions,
            base.history, base.perms, base.mode, base.llm, base.llmConfig, work, base.todos, "session", base.lsp,
            onImageReady = delivered::addAll, appContext = RuntimeEnvironment.getApplication())
        val result = AgentTools.execute("view_image", buildJsonObject { put("path", ref.path) }, context)
        assertTrue(result, result.startsWith("IMAGE_ATTACHED"))
        assertEquals(ref.id, delivered.single().mediaId)
        assertTrue(delivered.single().bytes < VisionSupport.MAX_IMAGE_BYTES)
        assertNotEquals(ref.path, delivered.single().path)
        assertArrayEquals(source, store.file(ref)!!.readBytes())
        assertTrue(VisionSupport.materializeHistory(listOf(VisionSupport.userMessage("inspect", delivered, 1_000)), work)
            .single().toString().contains("data:image/jpeg;base64,"))
    }

    @Test fun transparentDiagramKeepsReadableWhiteBackgroundInJpegPreview() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(16, 16, Color.BLACK)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val ref = ChatMediaStore(work).import("session", "diagram.png", "image/png", ByteArrayInputStream(png))
        val prepared = ChatMediaProcessing.prepareModelImages(work, listOf(ref), "session")
        val preview = BitmapFactory.decodeFile(ChatMediaStore(work).resolvePath(prepared.imagePaths.single())!!.absolutePath)
        try { assertTrue(Color.red(preview.getPixel(0, 0)) > 240) } finally { preview.recycle() }
    }

    @Test fun videosReceiveFairBoundedSlotsAndAlwaysReleaseRetriever() = runBlocking {
        val store = ChatMediaStore(work)
        val refs = (1..2).map { store.import("session", "clip$it.mp4", "video/mp4", ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val prepared = ChatMediaProcessing.prepareModelImages(work, refs, "session")
        assertEquals(4, prepared.imagePaths.size)
        assertEquals(4, BoundedRetrieverShadow.frames.size)
        assertEquals(2, BoundedRetrieverShadow.releases)
        assertTrue(BoundedRetrieverShadow.frames.all { maxOf(it.width, it.height) <= ChatMediaProcessing.VIDEO_EDGE && it.option == MediaMetadataRetriever.OPTION_CLOSEST_SYNC })
        assertTrue(prepared.description.contains("no audio"))
        assertTrue(prepared.description.contains("not a full video analysis"))
        assertTrue(prepared.imagePaths.sumOf { store.resolvePath(it)!!.length() } <= ChatMediaProcessing.MAX_INPUT_BYTES)
        ChatMediaProcessing.prepareModelImages(work, refs, "session")
        assertEquals("Cached frames do not decode again", 4, BoundedRetrieverShadow.frames.size)
        assertEquals(4, BoundedRetrieverShadow.releases)
    }

    @Test fun cancellationRecyclesCurrentFrameAndReleasesDecoder() = runBlocking {
        val store = ChatMediaStore(work)
        val ref = store.import("session", "clip.mp4", "video/mp4", ByteArrayInputStream(byteArrayOf(1)))
        val pending = async { ChatMediaProcessing.prepareModelImages(work, listOf(ref), "session") }
        BoundedRetrieverShadow.afterFrame = { pending.cancel() }
        try { pending.await(); fail("Canceled media work must not finish") } catch (_: CancellationException) { }
        assertEquals(1, BoundedRetrieverShadow.releases)
        assertTrue(BoundedRetrieverShadow.lastBitmap!!.isRecycled)
        assertNotNull(store.file(ref))
    }

    @Test fun rotatedVideoRequestsDisplayOrientedBoundsWithoutDoubleRotation() = runBlocking {
        val ref = ChatMediaStore(work).import("session", "portrait.mp4", "video/mp4", ByteArrayInputStream(byteArrayOf(1)))
        BoundedRetrieverShadow.rotation = "90"
        ChatMediaProcessing.prepareModelImages(work, listOf(ref), "session")
        assertTrue(BoundedRetrieverShadow.frames.all { it.width == 540 && it.height == 960 })
    }

    @Test fun revokedOwnerCannotStartVideoDecoder() = runBlocking {
        val store = ChatMediaStore(work)
        val ref = store.import("draft", "clip.mp4", "video/mp4", ByteArrayInputStream(byteArrayOf(1)))
        store.releaseOwner("removed-session")
        try { ChatMediaProcessing.prepareModelImages(work, listOf(ref), "removed-session"); fail("Owner revoked") } catch (_: IllegalArgumentException) { }
        assertEquals(0, BoundedRetrieverShadow.frames.size)
        assertEquals(0, BoundedRetrieverShadow.releases)
    }

    @Test fun admissionAndTargetCalculationsRejectUnboundedInput() {
        assertEquals(listOf(2, 2), ChatMediaProcessing.frameAllocation(listOf(true, true)))
        assertEquals(listOf(1, 2, 1), ChatMediaProcessing.frameAllocation(listOf(false, true, false)))
        assertEquals(listOf(4), ChatMediaProcessing.frameAllocation(listOf(true)))
        assertEquals(1280 to 1, ChatMediaProcessing.targetSize(Int.MAX_VALUE, 1, 1280))
        val all = ChatMediaProcessing.sampleTimes(10_000, 4)
        assertEquals(4, all.distinct().size)
        assertTrue(all.all { it in 1 until 10_000_000 })
        assertTrue(all.containsAll(ChatMediaProcessing.sampleTimes(10_000, 2)))
        for (action in listOf<() -> Any>(
            { ChatMediaProcessing.targetSize(0, 2, 1280) },
            { ChatMediaProcessing.sampleTimes(Long.MAX_VALUE, 1) },
            { ChatMediaProcessing.frameAllocation(List(5) { true }) },
        )) { try { action(); fail("Invalid media input accepted") } catch (_: IllegalArgumentException) { } }
    }

    private fun jpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        bitmap.recycle()
        val exif = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { data ->
            data.write(byteArrayOf(69, 120, 105, 102, 0, 0)); data.writeShort(0x4d4d); data.writeShort(42); data.writeInt(8)
            data.writeShort(1); data.writeShort(0x0112); data.writeShort(3); data.writeInt(1); data.writeShort(orientation); data.writeShort(0); data.writeInt(0)
        } }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { data ->
            data.write(jpeg, 0, 2); data.writeShort(0xffe1); data.writeShort(exif.size + 2); data.write(exif); data.write(jpeg, 2, jpeg.size - 2)
        } }.toByteArray()
    }
}

/**
 * Robolectric's native FileDescriptor source reports "Only supported on Android".
 * Replace only its host file-source adapter with fixture bytes; keep the real native
 * image decoder, EXIF, resizing, pixel postprocessor and JPEG encoder under test.
 * This does not verify Android's actual file-descriptor bridge or hardware codecs.
 */
@Implements(value = ImageDecoder::class, callNativeMethodsByDefault = true)
class HostImageFileSourceShadow : ShadowNativeImageDecoder() {
    companion object {
        @JvmStatic @Implementation fun createSource(file: File): ImageDecoder.Source =
            ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))
    }
}

@Implements(MediaMetadataRetriever::class)
class BoundedRetrieverShadow {
    @Implementation fun setDataSource(path: String) { check(File(path).isFile) }
    @Implementation fun extractMetadata(key: Int): String? = when (key) {
        MediaMetadataRetriever.METADATA_KEY_DURATION -> "10000"
        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH -> "3840"
        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT -> "2160"
        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION -> rotation
        else -> null
    }
    @Implementation fun getScaledFrameAtTime(time: Long, option: Int, width: Int, height: Int): Bitmap {
        frames += Frame(time, option, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { lastBitmap = it; afterFrame?.invoke() }
    }
    @Implementation fun release() { releases++ }
    data class Frame(val time: Long, val option: Int, val width: Int, val height: Int)
    companion object {
        val frames = mutableListOf<Frame>()
        var releases = 0
        var lastBitmap: Bitmap? = null
        var afterFrame: (() -> Unit)? = null
        var rotation = "0"
        fun reset() { frames.clear(); releases = 0; lastBitmap = null; afterFrame = null; rotation = "0" }
    }
}
