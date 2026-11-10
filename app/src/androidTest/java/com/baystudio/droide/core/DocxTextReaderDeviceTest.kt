package com.baystudio.droide.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored device gate: host JUnit does not establish Android Expat behavior. */
@RunWith(AndroidJUnit4::class)
class DocxTextReaderDeviceTest {
    private val files = mutableListOf<File>()
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun document(body: String) = "<w:document xmlns:w=\"$w\"><w:body>$body</w:body></w:document>"
    private fun paragraph(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"

    @After fun removeFixtures() { files.forEach { it.delete() } }

    @Test fun platformSaxAcceptsSafeDocxAndPreservesOriginalBytes() {
        val file = archive(document(paragraph("Hello 世界 😀") + "<w:tbl><w:tr><w:tc>" + paragraph("A") +
            "</w:tc><w:tc>" + paragraph("B") + "</w:tc></w:tr></w:tbl>").toByteArray())
        val original = file.readBytes()
        val readerClass = SAXParserFactory.newInstance().newSAXParser().xmlReader.javaClass.name
        val result = DocxTextReader.read(file)
        assertEquals("Platform parser: $readerClass", "Hello 世界 😀\nA\tB\n", result.text)
        assertFalse(result.truncated)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun platformSaxRejectsDoctypeAndEntitiesInUtf8AndUtf16() {
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE")) {
            for (declaration in listOf(
                "<!DOCTYPE w:document>",
                "<!DOCTYPE w:document SYSTEM \"file:///never-read-docx-test\">",
                "<!DOCTYPE w:document [<!ENTITY x SYSTEM \"http://127.0.0.1:1/never-docx-test\">]>",
                "<!DOCTYPE w:document [<!ENTITY % x SYSTEM \"file:///never-read-docx-test\">%x;]>",
            )) {
                val xml = "<?xml version=\"1.0\" encoding=\"$encoding\"?>$declaration" + document(paragraph("safe"))
                try {
                    DocxTextReader.read(archive(xml.toByteArray(Charset.forName(encoding))))
                    fail("Platform parser accepted DTD in $encoding")
                } catch (e: DocxTextReader.ReadException) {
                    assertEquals("Platform DTD rejection in $encoding", DocxTextReader.Reason.UNSAFE_XML, e.reason)
                }
            }
        }
    }

    @Test fun platformSaxAcceptsSafeUtf16AndChecksCancellation() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16LE\"?>" + document(paragraph("你好"))
        val file = archive(xml.toByteArray(Charset.forName("UTF-16LE")))
        assertEquals("你好\n", DocxTextReader.read(file).text)
        val cancellation = CancellationException("device fixture")
        var checks = 0
        try {
            DocxTextReader.read(file) { if (++checks == 20) throw cancellation }
            fail("Cancellation was ignored")
        } catch (e: CancellationException) { assertSame(cancellation, e) }
    }

    @Test fun platformSaxTruncatesWithoutBrokenSurrogatePairs() {
        val result = DocxTextReader.read(archive(document(paragraph(
            "x".repeat(DocxTextReader.MAX_OUTPUT_CHARS - 1) + "😀tail")).toByteArray()))
        assertTrue(result.truncated)
        assertEquals(DocxTextReader.MAX_OUTPUT_CHARS - 1, result.text.length)
        assertFalse(result.text.last().isHighSurrogate())
    }

    private fun archive(xml: ByteArray): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("docx-device-", ".docx", context.cacheDir)
        files.add(file)
        val types = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("[Content_Types].xml" to types.toByteArray(), "_rels/.rels" to rels.toByteArray(), "word/document.xml" to xml)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }
}
