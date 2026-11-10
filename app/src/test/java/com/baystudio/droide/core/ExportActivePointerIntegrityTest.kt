package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExportActivePointerIntegrityTest {
    @Test fun overlongPrivateJobIdIsQuarantinedWithoutInvalidPathCreation() {
        val root = Files.createTempDirectory("overlong-private-job").toFile()
        try {
            val journal = ExportJournal(root)
            journal.write(ExportJobState("active", "epoch", ExportJobPhase.WRITING))
            val pointer = File(root, "current.properties")
            val bad = pointer.readText().replace("id=active", "id=" + "x".repeat(256))
            pointer.writeText(bad)
            ExportPointerRecovery.open(root).use { assertTrue(it.recover().recovered) }
            assertFalse(pointer.exists())
            assertEquals(bad, root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun activeMalformedFieldsCannotBeSilentlyOverwritten() {
        val root = Files.createTempDirectory("active-pointer-integrity").toFile()
        try {
            val journal = ExportJournal(root)
            val original = ExportJobState("active", "epoch", ExportJobPhase.WRITING)
            journal.write(original)
            val pointer = File(root, "current.properties")
            val good = pointer.readText()
            val history = File(root, "active/state.properties").readBytes()
            val corruptions = listOf(
                good.replace("entries=0", "entries=-1"),
                good.replace("bytes=0", "bytes=-1"),
                good.replace("createdCount=0", "createdCount=-1"),
                good.replace("epoch=epoch", "epoch="),
                good.replace("id=active", "id=../outside"),
                good.replace("id=active", "id=" + "x".repeat(256)),
                good.replace("version=1", "version=broken"),
                good.replace("retainedCapture=false", "retainedCapture=garbage"),
                "version=99\n" + good,
            )
            for (bad in corruptions) {
                assertNotEquals(good, bad)
                pointer.writeText(bad)
                assertTrue(runCatching { journal.write(original.copy(id = "replacement")) }.isFailure)
                assertEquals(bad, pointer.readText())
                assertArrayEquals(history, File(root, "active/state.properties").readBytes())
                assertFalse(File(root, "replacement").exists())
            }
        } finally { root.deleteRecursively() }
    }
}
