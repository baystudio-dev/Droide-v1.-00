package com.baystudio.droide.core

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedMediaInput(val imagePaths: List<String>, val description: String)

/** One bounded platform decoder across conversations; never loads original media into chat state. */
object ChatMediaProcessing {
    const val MAX_IMAGES = 4
    const val MAX_INPUT_BYTES = 8L * 1024 * 1024
    const val IMAGE_EDGE = 1280
    const val VIDEO_EDGE = 960
    private const val JPEG_QUALITY = 85
    private val processor = Mutex()

    suspend fun prepareModelImages(workDir: File, media: List<ChatMediaRef>, ownerId: String): PreparedMediaInput =
        withContext(Dispatchers.IO) {
            val visual = media.distinctBy { it.id }.filter { it.mediaType.startsWith("image/") || it.mediaType.startsWith("video/") }
            if (visual.isEmpty()) return@withContext PreparedMediaInput(emptyList(), "")
            require(visual.size <= MAX_IMAGES) { "Attach at most four images or videos in one message." }
            processor.withLock {
                val store = ChatMediaStore(workDir)
                store.retain(ownerId, visual)
                val allocation = frameAllocation(visual.map { it.mediaType.startsWith("video/") })
                var bytes = 0L
                val paths = mutableListOf<String>()
                val descriptions = mutableListOf<String>()
                visual.forEachIndexed { index, ref ->
                    currentCoroutineContext().ensureActive()
                    val original = store.file(ref) ?: error("Attachment is expired or unavailable; choose the file again.")
                    val outputs = if (ref.mediaType.startsWith("image/")) {
                        listOf(imagePreview(store, ref, original))
                    } else {
                        videoPreviews(store, ref, original, allocation[index])
                    }
                    outputs.forEach { file ->
                        require(file.length() <= MAX_INPUT_BYTES - bytes) { "Media previews exceed the eight MiB message budget; send fewer attachments." }
                        bytes += file.length()
                        // Virtual chat paths are references only; physical media is outside project source.
                        paths += store.aliasForFile(file)
                    }
                    descriptions += if (ref.mediaType.startsWith("video/")) {
                        "Video ${ref.name}: ${outputs.size} sampled frames only; no audio was analyzed and this is not a full video analysis."
                    } else if (ref.mediaType == "image/gif") {
                        "Image ${ref.name}: a resized still preview; animation is not analyzed."
                    } else "Image ${ref.name}: resized preview."
                }
                currentCoroutineContext().ensureActive()
                PreparedMediaInput(paths, descriptions.joinToString("\n"))
            }
        }

    /** Give every attachment a slot before allocating remaining slots to videos. */
    internal fun frameAllocation(videos: List<Boolean>): List<Int> {
        require(videos.size <= MAX_IMAGES)
        val counts = IntArray(videos.size) { 1 }
        var remaining = MAX_IMAGES - videos.size
        while (remaining > 0 && videos.any { it }) {
            for (index in videos.indices) {
                if (videos[index] && remaining > 0) { counts[index]++; remaining-- }
            }
        }
        return counts.toList()
    }

    internal fun targetSize(width: Int, height: Int, longestEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longestEdge > 0) { "Media dimensions are invalid" }
        val scale = minOf(1.0, longestEdge.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    internal fun sampleTimes(durationMillis: Long, count: Int): List<Long> {
        require(durationMillis in 1..(Long.MAX_VALUE / 1000) && count in 1..MAX_IMAGES) { "Video duration is unavailable or invalid" }
        val durationMicros = durationMillis * 1000
        // Reuse the same four positions when another message gives this video a different slot budget.
        return listOf(1, 3, 0, 2).take(count).sorted().map { index ->
            (durationMicros / 8L) * (2L * index + 1L)
        }
    }

    private suspend fun imagePreview(store: ChatMediaStore, ref: ChatMediaRef, source: File): File {
        val leaf = "model-preview-v1-$IMAGE_EDGE.jpg"
        store.derivativeFile(ref, leaf)?.let { return it }
        currentCoroutineContext().ensureActive()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val (width, height) = targetSize(info.size.width, info.size.height, IMAGE_EDGE)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, height)
            // JPEG has no alpha; preserve dark diagram/text visibility against a white background.
            decoder.setPostProcessor { canvas ->
                canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                PixelFormat.OPAQUE
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            return storeBitmap(store, ref, leaf, bitmap)
        } finally { bitmap.recycle() }
    }

    private suspend fun videoPreviews(store: ChatMediaStore, ref: ChatMediaRef, source: File, count: Int): List<File> {
        val retriever = MediaMetadataRetriever()
        try {
            currentCoroutineContext().ensureActive()
            retriever.setDataSource(source.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("This video has no readable duration; attach a supported video or an image instead.")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: error("This video has no readable dimensions.")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: error("This video has no readable dimensions.")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            // The platform already rotates the returned frame; request bounds in its displayed orientation.
            val (targetWidth, targetHeight) = if (rotation == 90 || rotation == 270) {
                targetSize(height, width, VIDEO_EDGE)
            } else targetSize(width, height, VIDEO_EDGE)
            return sampleTimes(duration, count).map { timestamp ->
                currentCoroutineContext().ensureActive()
                val leaf = "video-preview-v1-$VIDEO_EDGE-$timestamp.jpg"
                store.derivativeFile(ref, leaf) ?: run {
                    val bitmap = retriever.getScaledFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
                        ?: error("This video frame could not be decoded; attach a supported video or an image instead.")
                    try {
                        currentCoroutineContext().ensureActive()
                        storeBitmap(store, ref, leaf, bitmap)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { retriever.release() }
    }

    private suspend fun storeBitmap(store: ChatMediaStore, ref: ChatMediaRef, leaf: String, bitmap: Bitmap): File {
        val output = BoundedJpegOutput()
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "Media preview could not be encoded" }
        currentCoroutineContext().ensureActive()
        return ByteArrayInputStream(output.toByteArray()).use { store.importDerivative(ref, leaf, it) }
    }

    private class BoundedJpegOutput : ByteArrayOutputStream(64 * 1024) {
        override fun write(value: Int) {
            if (count.toLong() >= ChatMediaStore.MAX_DERIVATIVE_BYTES) throw IOException("Media preview exceeds four MiB")
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length.toLong() > ChatMediaStore.MAX_DERIVATIVE_BYTES - count) throw IOException("Media preview exceeds four MiB")
            super.write(bytes, offset, length)
        }
    }
}
