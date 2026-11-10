package com.baystudio.droide.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.sqrt

internal data class AgentWebImageSize(val width: Int, val height: Int)

internal interface AgentWebPdfBackend {
    fun pageCount(file: File): Int
    fun text(file: File, page: Int): String?
    fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize
}

internal class AndroidAgentWebPdf : AgentWebPdfBackend {
    private fun <T> withPdf(file: File, block: (PdfRenderer) -> T): T {
        require(file.isFile && file.length() in 1..8L * 1024 * 1024) { "PDF is missing or exceeds 8 MiB" }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
        return renderer.use { block(it) }
    }

    override fun pageCount(file: File): Int = withPdf(file) { renderer ->
        renderer.pageCount.also { require(it in 1..10_000) { "PDF has no pages or exceeds the mobile page-count limit" } }
    }

    override fun text(file: File, page: Int): String? {
        if (Build.VERSION.SDK_INT < 35) return null
        return withPdf(file) { renderer ->
            require(page in 0 until renderer.pageCount) { "PDF page is out of range (zero-based)" }
            renderer.openPage(page).use { readText35(it) }
        }
    }

    @androidx.annotation.RequiresApi(35)
    private fun readText35(page: PdfRenderer.Page): String {
        val out = StringBuilder()
        for (content in page.textContents) {
            if (out.length > AgentWebDocuments.MAX_TEXT_CHARS) break
            out.append(content.text.take(AgentWebDocuments.MAX_TEXT_CHARS + 1 - out.length)).append('\n')
        }
        return out.toString()
    }

    override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = withPdf(file) { renderer ->
        require(page in 0 until renderer.pageCount) { "PDF page is out of range (zero-based)" }
        renderer.openPage(page).use { source ->
            val size = boundedSize(source.width, source.height)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(size.width.toFloat()/source.width, size.height.toFloat()/source.height) }
                source.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 92, LimitedOutputStream(output, 4L * 1024 * 1024))) { "PDF screenshot encoding failed" }
                size
            } finally { bitmap.recycle() }
        }
    }

    companion object {
        fun boundedSize(width: Int, height: Int): AgentWebImageSize {
            require(width > 0 && height > 0) { "Invalid PDF page dimensions" }
            val scale = min(1600.0 / maxOf(width, height), sqrt(2_000_000.0 / (width.toDouble() * height))).coerceAtMost(2.0)
            return AgentWebImageSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        }
    }
}

internal class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
    private var written = 0L
    override fun write(value: Int) { require(written + 1 <= limit) { "Generated image exceeds 4 MiB" }; out.write(value); written++ }
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        require(written + len <= limit) { "Generated image exceeds 4 MiB" }
        out.write(bytes, off, len); written += len
    }
}
