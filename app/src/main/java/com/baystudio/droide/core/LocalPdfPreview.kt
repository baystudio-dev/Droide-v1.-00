package com.baystudio.droide.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PdfPreviewPage(val pages: Int, val bitmap: Bitmap?, val text: String?,
    val textSupported: Boolean, val truncated: Boolean)

internal object LocalPdfPreview {
    private val admission = Semaphore(1)
    private val retirement = PdfPreviewRetirement()
    suspend fun confirmStoppedForExit() = admission.withPermit { retirement.requireStopped() }
    suspend fun read(context: Context, file: File, page: Int, text: Boolean, zoom: Int): PdfPreviewPage =
        admission.withPermit {
            retirement.requireStopped()
            val memory = if (text) null else SharedMemory.create("Droide PDF page", DocumentPreviewPolicy.MAX_PIXELS * 4)
            var unpublished: Bitmap? = null
            try {
                val response = withTimeout(DocumentPreviewPolicy.TIMEOUT_MS + 1_000) {
                    request(context.applicationContext, file, page, text, zoom, memory)
                }
                response.getString("error")?.let { throw IllegalArgumentException(it) }
                val completed = withContext(Dispatchers.Default) {
                    ensureActive()
                    val count = response.getInt("pages")
                    require(count in 1..DocumentPreviewPolicy.MAX_PAGES) { "Invalid PDF page count" }
                    var bitmap: Bitmap? = null
                    try {
                        if (!text) {
                            val width = response.getInt("width"); val height = response.getInt("height")
                            require(width in 1..3200 && height in 1..3200 && width.toLong() * height <= DocumentPreviewPolicy.MAX_PIXELS &&
                                width.toLong() * height * 4 <= requireNotNull(memory).size) { "Invalid PDF pixel size" }
                            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            unpublished = bitmap
                            val buffer = requireNotNull(memory).mapReadOnly()
                            try { bitmap.copyPixelsFromBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
                        }
                        ensureActive()
                        PdfPreviewPage(count, bitmap, response.getString("text")?.take(DocumentPreviewPolicy.MAX_TEXT_CHARS),
                            response.getBoolean("textSupported"), response.getBoolean("truncated"))
                    } catch (failure: Throwable) { bitmap?.recycle(); throw failure }
                }
                currentCoroutineContext().ensureActive()
                unpublished = null
                completed
            } catch (failure: Throwable) { unpublished?.let { if (!it.isRecycled) it.recycle() }; throw failure
            } finally { memory?.close() }
        }

    private suspend fun request(context: Context, file: File, page: Int, text: Boolean, zoom: Int,
        memory: SharedMemory?): Bundle = withContext(Dispatchers.Main.immediate) {
        var bound = false
        var connection: ServiceConnection? = null
        var remote: IBinder? = null
        val retired = CompletableDeferred<Unit>()
        val retirementToken = Any()
        try {
            suspendCancellableCoroutine { continuation ->
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == 2 && continuation.isActive) continuation.resume(message.data)
                    true
                })
                val callback = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!continuation.isActive || remote != null) return
                        remote = service
                        retirement.started(retirementToken)
                        try { service.linkToDeath({ retirement.died(retirementToken); retired.complete(Unit) }, 0) }
                        catch (_: RemoteException) { retirement.died(retirementToken); retired.complete(Unit) }
                        try {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                                Messenger(service).send(Message.obtain().apply {
                                    what = 1; replyTo = reply
                                    data = Bundle().apply {
                                        putParcelable("input", descriptor); putParcelable("pixels", memory)
                                        putInt("page", page); putBoolean("text", text); putInt("zoom", zoom)
                                    }
                                })
                            }
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF preview worker stopped. Try another page or reopen the file."))
                    }
                    override fun onNullBinding(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF preview service is unavailable"))
                    }
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                }
                connection = callback
                try {
                    bound = context.bindIsolatedService(Intent(context, PdfPreviewService::class.java),
                        Context.BIND_AUTO_CREATE, "preview" + UUID.randomUUID().toString().replace("-", ""),
                        Executor { command -> Handler(Looper.getMainLooper()).post(command) }, callback)
                    if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("Cannot start PDF preview"))
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } finally {
            // Non-suspending cleanup runs even when canceled; each request has its own isolated instance.
            if (bound) connection?.let { runCatching { context.unbindService(it) } }
            // Hold the single native-work permit until Binder confirms process death. Never overlap a stuck
            // native parser with replacement work. Watchdog remains independent of parsing.
            if (remote != null) withContext(NonCancellable) {
                withTimeoutOrNull(DocumentPreviewPolicy.TIMEOUT_MS + 1_000) { retired.await() }

            }
        }
    }
}
