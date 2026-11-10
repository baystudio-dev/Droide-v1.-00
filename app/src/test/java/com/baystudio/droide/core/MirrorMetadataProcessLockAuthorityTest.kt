package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorMetadataProcessLockAuthorityTest {
    private class Fixture {
        val root = Files.createTempDirectory("metadata-lock-authority-").toFile()
        val source = File(root, "source").apply { mkdir() }
        val working = File(root, "working")
        val directory = File(root, "authority")
        val initial = MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working)
        val database get() = File(directory, "state.sqlite")
        fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working, processCheckpoint = checkpoint)
        fun contender(): String {
            val binding = File(directory, "identity").readLines()[1]
            val classpath = checkNotNull(System.getProperty("droide.custody.test.classpath"))
            val args = File(root, "child.args")
            val values = listOf("-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}",
                "-Djava.library.path=${System.getProperty("java.library.path")}", "-cp", classpath,
                MirrorMetadataProcessLockFixture::class.java.name, "attempt", directory.path, binding)
            args.writeText(values.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "@${args.path}")
                .redirectErrorStream(true).apply {

                    environment().remove("JAVA_TOOL_OPTIONS")
                }.start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Metadata contender did not terminate" }
                return process.inputStream.bufferedReader().readText().trim()
            } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        }
    }
    private fun fixture(action: Fixture.() -> Unit) {
        val f = Fixture()
        try { f.action() } finally { f.root.deleteRecursively() }
    }

    @Test fun cooperatingChildCannotEnterBetweenSidecarInspectionAndDatabaseOpen() = fixture {
        var observed = false
        val before = initial.load()
        val guarded = authority {
            if (it == "metadata-before-database-open") { observed = true; assertEquals("DENIED", contender()) }
        }
        assertEquals(before, guarded.load()); assertTrue(observed)
        assertEquals("ACQUIRED", contender())
    }

    @Test fun rawCaptureSharesExclusionAndNeverAdmitsJournal() = fixture {
        val before = database.readBytes()
        File(directory, "state.sqlite-journal").writeText("unresolved evidence")
        var observed = false
        val guarded = authority {
            if (it == "sqlite-review-after-database-copy") { observed = true; assertEquals("DENIED", contender()) }
        }
        guarded.captureSqliteRollbackEvidence {}
        assertTrue(observed); assertArrayEquals(before, database.readBytes())
        assertEquals("unresolved evidence", File(directory, "state.sqlite-journal").readText())
        assertNotNull(runCatching { guarded.load() }.exceptionOrNull())
    }

    @Test fun replacedLockIdentityRefusesBeforeSQLiteOpen() = fixture {
        val before = database.readBytes()
        val lock = File(directory, MirrorMetadataProcessLock.LOCK)
        Files.move(lock.toPath(), File(directory, "preserved-lock").toPath())
        lock.createNewFile()
        var opened = false
        val guarded = authority { if (it == "metadata-before-database-open") opened = true }
        assertNotNull(runCatching { guarded.load() }.exceptionOrNull())
        assertFalse(opened); assertArrayEquals(before, database.readBytes())
    }

    @Test fun compatibleEnrollmentLeavesExistingDatabaseBytesAndRevisionUnchanged() = fixture {
        val state = initial.load(); val before = database.readBytes()
        // Disposable fixture simulates an authority, which has neither lock artifact.
        Files.delete(File(directory, MirrorMetadataProcessLock.IDENTITY).toPath())
        Files.delete(File(directory, MirrorMetadataProcessLock.LOCK).toPath())
        assertEquals(state, authority().load())
        assertArrayEquals(before, database.readBytes())
        assertTrue(File(directory, MirrorMetadataProcessLock.IDENTITY).isFile)
    }
}
