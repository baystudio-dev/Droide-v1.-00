package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class F151DirectoryDeleteLateChildProbe {
    @Test fun successfulSyncMustNotEraseLateDescendantWriteThroughOpenDescriptor() = runBlocking {
        val root = Files.createTempDirectory("f150-directory-delete-late-child-").toFile()
        val source = root.resolve("src").apply { check(mkdir()) }
        val child = source.resolve("main.kt").apply { writeText("original") }
        lateinit var stage: File
        var sameTopLevelMetadata = false
        RandomAccessFile(child, "rw").use { writer ->
            val repo = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Delete) {
                    stage = root.listFiles()!!.singleOrNull { it.name.contains(".droide-tmp-") }
                        ?: root.resolve(".droide/local-delete-custody").listFiles()!!.single().resolve("original")
                    val before = Files.readAttributes(stage.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    writer.setLength(0); writer.write("newer unsaved CLI child work".toByteArray()); writer.fd.sync()
                    val after = Files.readAttributes(stage.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    sameTopLevelMetadata = before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()
                }
            })
            val outcome = runCatching { repo.delete("src") }
            assertTrue("The descendant edit is invisible to the current top-level stage comparison", sameTopLevelMetadata)
            assertFalse("The original pathname has been staged", source.exists())
            assertTrue("Late descendant work must stay reachable after successful synchronization; actual result=$outcome and stage=${stage.path}", stage.resolve("main.kt").exists())
            assertEquals("newer unsaved CLI child work", stage.resolve("main.kt").readText())
        }
        // Keep this small synthetic fixture as RED evidence; do not delete retained bytes.
    }

    @Test fun ordinaryFileDeleteStillCompletesWithItsObserver() = runBlocking {
        val root = Files.createTempDirectory("f150-file-delete-control-").toFile()
        root.resolve("file.txt").writeText("original")
        var observed = 0
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Delete) observed++ })
        assertTrue(repo.delete("file.txt"))
        assertEquals(1, observed)
        assertFalse(root.resolve("file.txt").exists())
    }
}
