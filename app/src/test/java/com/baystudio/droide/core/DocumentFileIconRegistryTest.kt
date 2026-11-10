package com.baystudio.droide.core

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DocumentFileIconRegistryTest {
    @Test fun pdfNamesAreCaseInsensitive() {
        listOf("report.pdf", "REPORT.PDF", "Report.PdF", ".pdf", "/workspace/notes/report.PDF",
            "C:\\notes\\report.PdF").forEach { assertEquals(it, "pdf", FileIconRegistry.iconNameFor(it)) }
    }

    @Test fun docxNamesAreCaseInsensitive() {
        listOf("report.docx", "REPORT.DOCX", "Report.DoCx", ".docx", "/workspace/notes/report.DOCX",
            "C:\\notes\\report.DoCx").forEach { assertEquals(it, "word", FileIconRegistry.iconNameFor(it)) }
    }

    @Test fun parentFolderSuffixDoesNotSelectDocumentIcon() {
        listOf("/folder.pdf/README", "C:\\folder.docx\\README").forEach {
            assertEquals(it, "markdown", FileIconRegistry.iconNameFor(it))
        }
        listOf("/folder.pdf/unknown", "C:\\folder.docx\\unknown").forEach {
            assertEquals(it, "default_file", FileIconRegistry.iconNameFor(it))
        }
    }

    @Test fun deceptiveAndMissingExtensionsRetainFallback() {
        listOf("report.pdf.tmp", "report.docx.tmp", "report.pdf.", "report.docx.", "pdf", "docx",
            "report.pdfx", "report.docxx", "", "/", "report.docx ").forEach {
            assertEquals(it, "default_file", FileIconRegistry.iconNameFor(it))
        }
        assertEquals("zip", FileIconRegistry.iconNameFor("report.docx.zip"))
        assertEquals("zip", FileIconRegistry.iconNameFor("report.pdf.tar.gz"))
    }

    @Test fun otherWordFormatsDoNotGainIconOrViewerSupport() {
        listOf("doc", "docm", "dot", "dotx", "dotm").forEach {
            val path = "report.${it.uppercase()}"
            assertEquals(path, "default_file", FileIconRegistry.iconNameFor(path))
            assertEquals(path, DocumentPreviewPolicy.Kind.LEGACY_WORD, DocumentPreviewPolicy.kind(path))
            assertFalse(path, FileIconRegistry.isTextFile(path))
        }
    }

    @Test fun documentIconsKeepBinaryReadOnlyPreviewClassification() {
        listOf("report.PDF", "report.DOCX").forEach {
            assertFalse(it, FileIconRegistry.isTextFile(it))
            assertFalse(it, FileIconRegistry.isImageFile(it))
        }
        assertEquals(DocumentPreviewPolicy.Kind.PDF, DocumentPreviewPolicy.kind("report.PDF"))
        assertEquals(DocumentPreviewPolicy.Kind.DOCX, DocumentPreviewPolicy.kind("report.DOCX"))
    }

    @Test fun fallbackLabelsRemainShortAndUseful() {
        assertEquals("PDF", FileIconRegistry.fallbackToken("report.PdF"))
        assertEquals("DOC", FileIconRegistry.fallbackToken("report.DoCx"))
        assertEquals("DOC", FileIconRegistry.fallbackToken("report.DOC"))
        assertEquals("TMP", FileIconRegistry.fallbackToken("report.docx.tmp"))
        assertEquals("FILE", FileIconRegistry.fallbackToken(""))
    }

    @Test fun existingNamedAndCompoundRoutesRemainIntact() {
        mapOf("AndroidManifest.xml" to "android", "BUILD.GRADLE.KTS" to "gradle",
            "README.md" to "markdown", ".env" to "env", "index.d.ts" to "typescript",
            "a.test.tsx" to "react", "icon.svg" to "image", "main.py" to "python").forEach { (path, expected) ->
            assertEquals(path, expected, FileIconRegistry.iconNameFor(path))
        }
    }

    @Test fun localeDoesNotChangeDocumentRoutes() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("word", FileIconRegistry.iconNameFor("INDIGO.DOCX"))
            assertEquals("pdf", FileIconRegistry.iconNameFor("INDIGO.PDF"))
        } finally { Locale.setDefault(previous) }
    }
}
