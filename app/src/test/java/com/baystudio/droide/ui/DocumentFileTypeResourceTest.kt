package com.baystudio.droide.ui

import com.baystudio.droide.R
import org.junit.Assert.*
import org.junit.Test

class DocumentFileTypeResourceTest {
    @Test fun pdfUsesLocalPdfArtwork() {
        listOf("a.pdf", "A.PDF", "folder/A.PdF", "C:\\folder\\A.PDF").forEach {
            assertEquals(it, R.drawable.filetype_pdf, fileTypeResource(it))
        }
    }

    @Test fun docxUsesLocalWordArtwork() {
        listOf("a.docx", "A.DOCX", "folder/A.DoCx", "C:\\folder\\A.DOCX").forEach {
            assertEquals(it, R.drawable.filetype_word, fileTypeResource(it))
        }
    }

    @Test fun documentResourcesAreDistinct() {
        assertNotEquals(fileTypeResource("a.pdf"), fileTypeResource("a.docx"))
    }

    @Test fun legacyWordFormatsUseWordArtwork() {
        listOf("a.doc", "a.DOCM", "a.dot", "a.DOTX", "a.dotm").forEach {
            assertEquals(it, R.drawable.filetype_word, fileTypeResource(it))
        }
    }

    @Test fun suffixAndUnknownPathsRetainFallback() {
        listOf("a.pdf.tmp", "a.docxx", "docx", "pdf", "", "/foo.pdf/bar").forEach {
            assertNull(it, fileTypeResource(it))
        }
        assertEquals(R.drawable.filetype_zip, fileTypeResource("a.docx.zip"))
    }

    @Test fun existingBrandResourcesRemainUnchanged() {
        assertEquals(R.drawable.filetype_python, fileTypeResource("a.py"))
        assertEquals(R.drawable.filetype_kotlin, fileTypeResource("a.kt"))
        assertEquals(R.drawable.filetype_markdown, fileTypeResource("README.md"))
        assertEquals(R.drawable.filetype_typescript, fileTypeResource("a.d.ts"))
        assertEquals(R.drawable.filetype_gradle, fileTypeResource("build.gradle.kts"))
    }

    @Suppress("DEPRECATION")
    @Test fun compatibilityResolverUsesSameNewAssets() {
        listOf("a.PDF", "a.DOCX", "a.unknown").forEach {
            assertEquals(fileTypeResource(it), prototypeFileTypeResource(it))
        }
    }
}
