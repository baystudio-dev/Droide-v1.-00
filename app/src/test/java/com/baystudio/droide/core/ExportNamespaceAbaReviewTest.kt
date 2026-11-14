package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExportNamespaceAbaReviewTest {
    @Test fun firstCaptureCannotBorrowUnselectedInodeMetadataDuringParentAba() {
        val home = Files.createTempDirectory("review-export-aba-").toFile()
        try {
            val root = File(home,"project").apply { mkdir() }
            val selected = File(root,"selected").apply { mkdir() }
            val other = File(root,"other").apply { mkdir() }
            val saved = File(root,"saved")
            val source = File(selected,"same.apk").apply { writeText("identical payload") }
            val replacement = File(other,"same.apk").apply { writeText("identical payload") }
            Files.setAttribute(source.toPath(), "unix:mode", 0b110100100) // 0644
            Files.setAttribute(replacement.toPath(), "unix:mode", 0b111000000) // 0700
            val initial = ExportFileStamp.read(source.toPath())
            var validations = 0
            var swapped = false
            fun restore() {
                if(swapped) {
                    Files.move(selected.toPath(),other.toPath())
                    Files.move(saved.toPath(),selected.toPath())
                    swapped=false
                }
            }
            val permit = object : VerifiedFileExportCapturePermit {
                override val root = root
                override val scope = ExportScope("selected/same.apk")
                override val verifySingleFile = true
                override val selectedFileIdentity = initial.identity
                override val stabilityAssumptions = emptyList<String>()
                override fun validate() {
                    if(++validations==2) {
                        Files.move(selected.toPath(),saved.toPath())
                        Files.move(other.toPath(),selected.toPath())
                        swapped=true
                    }
                }
            }
            val control = ExportControl(onProgress={ if(it.entries==1L) restore() })
            try {
                val capture = ExportCaptureStore(File(home,"staging")).capture(permit,control)
                capture.use {
                    var recorded:ExportEntry?=null
                    ExportManifest.each(it.manifest,it.manifestDigest){ entry->recorded=entry }
                    assertEquals("Original stamp must still hold after parent-only ABA",initial,ExportFileStamp.read(source.toPath()))
                    assertEquals("Capture used the selected ticket inode", "${initial.device}:${initial.inode}", recorded!!.inode)
                    assertEquals("Capture used selected inode mode",initial.mode,recorded!!.mode)
                }
            } catch(expected:ExportFailure) {
                assertTrue(expected.category in listOf("SourceChanged","ScopeIdentityChanged"))
            } finally { restore() }
        } finally { home.deleteRecursively() }
    }
}
