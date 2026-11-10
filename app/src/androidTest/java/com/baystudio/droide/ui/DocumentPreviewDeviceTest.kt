package com.baystudio.droide.ui

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baystudio.droide.core.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored device coverage; this suite must run on API29 and API35+ before shipping. */
@RunWith(AndroidJUnit4::class)
class DocumentPreviewDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample(file: File) {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawText("Droide preview test", 20f, 50f, Paint().apply { textSize = 18f })
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
    }
    @Test fun isolatedPdfRendersWithoutChangingInputAndTextIsVersionGated() = runBlocking {
        val file = File.createTempFile("pdf-preview-device", ".pdf", context.cacheDir)
        try {
            sample(file); val original = file.readBytes()
            val page = LocalPdfPreview.read(context, file, 0, false, 1)
            assertEquals(1, page.pages); assertNotNull(page.bitmap)
            val bitmap = requireNotNull(page.bitmap)
            assertTrue(bitmap.width.toLong() * bitmap.height <= DocumentPreviewPolicy.MAX_PIXELS)
            bitmap.recycle()
            val text = LocalPdfPreview.read(context, file, 0, true, 1)
            assertEquals(Build.VERSION.SDK_INT >= 35, text.textSupported)
            if (Build.VERSION.SDK_INT >= 35) assertTrue(text.text.orEmpty().contains("Droide preview"))
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun cancellationRetiresWorkerBeforeNextRequest() = runBlocking {
        val file = File.createTempFile("pdf-cancel-device", ".pdf", context.cacheDir)
        try {
            sample(file)
            val pending = launch { LocalPdfPreview.read(context, file, 0, false, 1).bitmap?.recycle() }
            delay(25); pending.cancelAndJoin()
            val next = withTimeout(45_000) { LocalPdfPreview.read(context, file, 0, false, 1) }
            assertEquals(1, next.pages); next.bitmap?.recycle()
        } finally { file.delete() }
    }
    @Test fun editorDocumentPreviewsCannotBecomeSaveableText() = runBlocking {
        val root = File(context.cacheDir, "document-route-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        try {
            listOf("raw.pdf", "raw.docx", "raw.doc", "raw.docm").forEach { name ->
                val file = File(root, name); val bytes = "%PDF-1.4 ASCII fixture".toByteArray(); file.writeBytes(bytes)
                val document = EditorDocument(name); document.ensureLoaded(FileRepository(root))
                assertEquals(EditorDocumentKind.DOCUMENT, document.kind)
                assertFalse(document.editable); assertFalse(document.canEdit); assertFalse(document.fullIntelligence)
                assertFalse(document.dirty); assertEquals("", document.content)
                assertTrue(document.save(FileRepository(root)).isFailure); assertArrayEquals(bytes, file.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
