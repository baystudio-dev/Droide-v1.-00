package com.baystudio.droide.core

import android.app.Application
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.*
import java.util.concurrent.Executors

/** One request per isolated binding. The service receives only a read-only snapshot descriptor. */
class PdfPreviewService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var accepted = false
    private val stop = Runnable { if (isPreviewProcess()) Process.killProcess(Process.myPid()) }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!accepted && message.what == 1) {
            accepted = true
            val reply = message.replyTo
            val data = message.data
            main.postDelayed(stop, DocumentPreviewPolicy.TIMEOUT_MS)
            worker.execute {
                val response = try { render(data) } catch (_: SecurityException) {
                    Bundle().apply { putString("error", "Password-protected or unsupported PDF security. Open it in a compatible app.") }
                } catch (_: OutOfMemoryError) {
                    Bundle().apply { putString("error", "This PDF page exceeds the preview memory budget.") }
                } catch (failure: Exception) {
                    Bundle().apply { putString("error", failure.message?.take(180) ?: "Invalid or unsupported PDF") }
                }
                main.post {
                    runCatching { reply?.send(Message.obtain().apply { what = 2; this.data = response }) }
                }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        check(isPreviewProcess()) { "PDF rendering requires its isolated preview process" }
    }
    override fun onBind(intent: Intent): IBinder = receiver.binder
    override fun onDestroy() {
        main.removeCallbacks(stop)
        worker.shutdownNow()
        super.onDestroy()
        // Native parsing may ignore interruption. Retire this unique, isolated service process.
        if (isPreviewProcess()) Process.killProcess(Process.myPid())
    }
    private fun isPreviewProcess() = Application.getProcessName().contains(":document_preview")

    @Suppress("DEPRECATION")
    private fun render(data: Bundle): Bundle {
        data.classLoader = javaClass.classLoader
        val input = requireNotNull(data.getParcelable<ParcelFileDescriptor>("input")) { "Missing document" }
        val memory = data.getParcelable<SharedMemory>("pixels")
        try {
            require(input.statSize in 1..DocumentPreviewPolicy.MAX_FILE_BYTES) { "Invalid PDF size" }
            val renderer = try { PdfRenderer(input) } catch (failure: Throwable) { input.close(); throw failure }
            return renderer.use { pdf ->
                require(pdf.pageCount in 1..DocumentPreviewPolicy.MAX_PAGES) { "PDF exceeds the 10,000-page preview limit" }
                val pageIndex = data.getInt("page")
                require(pageIndex in 0 until pdf.pageCount) { "PDF page is unavailable" }
                pdf.openPage(pageIndex).use { page ->
                    Bundle().apply {
                        putInt("pages", pdf.pageCount)
                        if (data.getBoolean("text")) {
                            putBoolean("textSupported", Build.VERSION.SDK_INT >= 35)
                            if (Build.VERSION.SDK_INT >= 35) readText(page, this)
                        } else {
                            val output = requireNotNull(memory) { "Missing pixel buffer" }
                            val (width, height) = DocumentPreviewPolicy.size(page.width, page.height, data.getInt("zoom", 1))
                            require(width.toLong() * height * 4 <= output.size) { "Invalid pixel buffer" }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, Matrix().apply { setScale(width.toFloat()/page.width, height.toFloat()/page.height) },
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val buffer = output.mapReadWrite()
                                try { bitmap.copyPixelsToBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
                                putInt("width", width); putInt("height", height)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally { runCatching { input.close() }; memory?.close() }
    }

    @androidx.annotation.RequiresApi(35)
    private fun readText(page: PdfRenderer.Page, result: Bundle) {
        val text = StringBuilder()
        var truncated = false
        for (part in page.textContents) {
            val remaining = DocumentPreviewPolicy.MAX_TEXT_CHARS - text.length
            if (remaining <= 0) { truncated = true; break }
            text.append(part.text.take(remaining))
            if (part.text.length > remaining) { truncated = true; break }
            if (text.length < DocumentPreviewPolicy.MAX_TEXT_CHARS) text.append('\n')
        }
        result.putString("text", text.toString())
        result.putBoolean("truncated", truncated)
    }
}
