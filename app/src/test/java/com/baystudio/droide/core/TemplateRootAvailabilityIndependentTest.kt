package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Test

class TemplateRootAvailabilityIndependentTest {
    private fun fixture(): File = Files.createTempDirectory("f165-independent-root-").toFile()

    private fun requireFailure(root: File) {
        println("TEMPLATE_WRITER_ORIGIN " + ProjectTemplateWriter::class.java.protectionDomain.codeSource.location)
        var failure: Exception? = null
        try { ProjectTemplateWriter.write(root, checkNotNull(ProjectTemplateRegistry.byId("empty"))) }
        catch (caught: Exception) { failure = caught }
        assertNotNull("An unavailable or symbolic registered root must be rejected, even for Empty", failure)
    }

    @Test fun missingRegisteredRootIsNotSilentlyRecreated() {
        val parent = fixture()
        val root = File(parent, "registered-but-missing")
        assertFalse(Files.exists(root.toPath(), NOFOLLOW_LINKS))
        requireFailure(root)
        assertFalse("Template writer must not recreate a missing registered root", Files.exists(root.toPath(), NOFOLLOW_LINKS))
    }

    @Test fun symbolicRegisteredRootIsNotAcceptedAsAnEmptyWorkspace() {
        val parent = fixture()
        val outside = File(parent, "outside").apply { mkdir() }
        val sentinel = File(outside, "keep.txt").apply { writeText("existing external content") }
        val root = File(parent, "registered-link")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        requireFailure(root)
        assertTrue(Files.isSymbolicLink(root.toPath()))
        assertEquals("existing external content", sentinel.readText())
        assertEquals(setOf("keep.txt"), outside.list()!!.toSet())
    }

    @Test fun genuineExistingRootStillAcceptsEmptyWithoutChangingFiles() {
        val root = fixture()
        val sentinel = File(root, "keep.txt").apply { writeText("retained workspace") }
        ProjectTemplateWriter.write(root, checkNotNull(ProjectTemplateRegistry.byId("empty")))
        assertEquals("retained workspace", sentinel.readText())
        assertEquals(setOf("keep.txt"), root.list()!!.toSet())
    }
}
