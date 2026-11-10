package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MirrorMetadataProcessLockTest {
    private val binding = "a".repeat(64)
    private class Interrupted : RuntimeException()
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("metadata-process-lock-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun <T> access(root: File, action: (() -> Unit) -> T) = MirrorMetadataProcessLock.access(root, binding, action = action)
    private fun child(root: File, mode: String): Process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}",
        "-Djava.library.path=${System.getProperty("java.library.path")}", "-cp", (System.getProperty("droide.custody.test.classpath") ?: System.getProperty("java.class.path")),
        MirrorMetadataProcessLockFixture::class.java.name, mode, root.path,
    ).redirectErrorStream(true).apply {

        environment().remove("JAVA_TOOL_OPTIONS")
    }.start()
    private fun attempt(root: File): String {
        val process = child(root, "attempt")
        try {
            assertTrue("Child did not terminate", process.waitFor(15, TimeUnit.SECONDS))
            return process.inputStream.bufferedReader().readText().trim()
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    @Test fun stableInodeAndIdentitySurviveRepeatedAccess() = fixture { root ->
        repeat(3) { access(root) { requireHeld -> requireHeld() } }
        assertEquals(0, File(root, MirrorMetadataProcessLock.LOCK).length())
        assertTrue(File(root, MirrorMetadataProcessLock.IDENTITY).readText().startsWith("DROIDE-METADATA-LOCK-1\n"))
        assertEquals("ACQUIRED", attempt(root))
    }

    @Test(timeout = 30000) fun crossJvmContentionIsDeniedAndForceKillReleasesOnlyItsLock() = fixture { root ->
        val holder = child(root, "hold")
        try {
            assertEquals("HELD", holder.inputStream.bufferedReader().readLine())
            assertNotNull(runCatching { access(root) { error("must not enter") } }.exceptionOrNull())
            assertEquals("DENIED", attempt(root))
            holder.destroyForcibly(); assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
            assertEquals("ACQUIRED", attempt(root))
            access(root) { it() }
        } finally { if (holder.isAlive) holder.destroyForcibly().waitFor() }
    }

    @Test fun independentWorkspacesDoNotShareOsLock() = fixture { root -> fixture { other ->
        access(root) {
            assertEquals("ACQUIRED", attempt(other))
            assertEquals("DENIED", attempt(root))
        }
    } }

    @Test fun reentryFailsBeforeDescriptorOpenAndCannotDropOuterLock() = fixture { root ->
        access(root) { held ->
            assertNotNull(runCatching { access(root) { error("reentry") } }.exceptionOrNull())
            held(); assertEquals("DENIED", attempt(root))
        }
        assertEquals("ACQUIRED", attempt(root))
    }

    @Test fun hardlinkedAliasFailsBeforeOpenAndCannotDropIncumbentLock() = fixture { root -> fixture { other ->
        access(root) {
            val alias = File(other, MirrorMetadataProcessLock.LOCK).toPath()
            Files.createLink(alias, File(root, MirrorMetadataProcessLock.LOCK).toPath())
            try { assertNotNull(runCatching { access(other) { error("hardlinked alias") } }.exceptionOrNull()) }
            finally { Files.delete(alias) }
            assertEquals("DENIED", attempt(root))
        }
    } }

    @Test fun replacedLockInodeIsRejectedEvenByFreshJvm() = fixture { root ->
        access(root) { it() }
        val marker = File(root, MirrorMetadataProcessLock.IDENTITY).readBytes()
        val lock = File(root, MirrorMetadataProcessLock.LOCK)
        Files.move(lock.toPath(), File(root, "preserved-lock").toPath())
        lock.createNewFile()
        assertEquals("DENIED", attempt(root))
        assertArrayEquals(marker, File(root, MirrorMetadataProcessLock.IDENTITY).readBytes())
        assertTrue(File(root, "preserved-lock").exists())
    }

    @Test fun markerPublicationInterruptionsRetainEvidenceAndRetrySafely() {
        for (boundary in listOf("metadata-lock-before-identity-publication", "metadata-lock-after-identity-publication")) fixture { root ->
            assertTrue(runCatching {
                MirrorMetadataProcessLock.access(root, binding, checkpoint = { if (it == boundary) throw Interrupted() }) { error("must not enter") }
            }.exceptionOrNull() is Interrupted)
            val before = root.listFiles()!!.associate { it.name to it.readBytes().toList() }
            access(root) { it() }
            before.forEach { (name, bytes) -> assertEquals(bytes, File(root, name).readBytes().toList()) }
            assertEquals("ACQUIRED", attempt(root))
        }
    }

    @Test fun mismatchedBindingAndCorruptIdentityCannotReenroll() = fixture { root ->
        access(root) { it() }
        assertNotNull(runCatching { MirrorMetadataProcessLock.access(root, "b".repeat(64)) {} }.exceptionOrNull())
        val marker = File(root, MirrorMetadataProcessLock.IDENTITY).apply { appendText("changed") }
        val before = marker.readBytes()
        assertEquals("DENIED", attempt(root)); assertArrayEquals(before, marker.readBytes())
    }

    @Test fun exceptionReleasesLeaseAndPreservesIdentity() = fixture { root ->
        assertTrue(runCatching { access(root) { throw Interrupted() } }.exceptionOrNull() is Interrupted)
        val before = File(root, MirrorMetadataProcessLock.IDENTITY).readBytes()
        assertEquals("ACQUIRED", attempt(root))
        assertArrayEquals(before, File(root, MirrorMetadataProcessLock.IDENTITY).readBytes())
    }

    @Test(timeout = 30000) fun creatorDescriptorClosesBeforeOtherLocalThreadCanAcquire() = fixture { root ->
        val created = java.util.concurrent.CountDownLatch(1)
        val closeCreator = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val contenderStarted = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                MirrorMetadataProcessLock.access(root, binding, checkpoint = {
                    if (it == "metadata-lock-created-before-close") {
                        created.countDown(); check(closeCreator.await(10, TimeUnit.SECONDS))
                    }
                }) { entered.countDown(); check(finish.await(10, TimeUnit.SECONDS)) }
            }
            assertTrue(created.await(10, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> {
                contenderStarted.countDown()
                runCatching { access(root) { error("must not enter") } }.isFailure
            }
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS))
            closeCreator.countDown(); assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(second.get(10, TimeUnit.SECONDS))
            assertEquals("DENIED", attempt(root))
            finish.countDown(); first.get(10, TimeUnit.SECONDS)
            assertEquals("ACQUIRED", attempt(root))
        } finally { closeCreator.countDown(); finish.countDown(); pool.shutdownNow() }
    }

    @Test fun identityAliasCannotCloseAnotherAuthoritysLiveLock() = fixture { root -> fixture { other ->
        access(root) {
            val alias = File(other, MirrorMetadataProcessLock.IDENTITY).toPath()
            Files.createLink(alias, File(root, MirrorMetadataProcessLock.LOCK).toPath())
            try { assertNotNull(runCatching { access(other) { error("identity alias") } }.exceptionOrNull()) }
            finally { Files.delete(alias) }
            assertEquals("DENIED", attempt(root))
        }
    } }

    @Test fun cancellationCallbackReleasesAllDescriptorsBeforeRetry() = fixture { root ->
        var checks = 0
        assertTrue(runCatching {
            MirrorMetadataProcessLock.access(root, binding, requireCurrent = { if (++checks == 3) throw Interrupted() }) {}
        }.exceptionOrNull() is Interrupted)
        assertEquals("ACQUIRED", attempt(root))
    }

    @Test fun symlinkLockIsNeverFollowed() = fixture { root -> fixture { other ->
        val outside = File(other, "outside").apply { writeText("preserve") }
        Files.createSymbolicLink(File(root, MirrorMetadataProcessLock.LOCK).toPath(), outside.toPath())
        assertNotNull(runCatching { access(root) { error("symlink") } }.exceptionOrNull())
        assertEquals("preserve", outside.readText()); assertFalse(File(root, MirrorMetadataProcessLock.IDENTITY).exists())
    } }
}

/** Separate actual JVM, not an in-memory lock simulation. */
object MirrorMetadataProcessLockFixture {
    @JvmStatic fun main(args: Array<String>) {
        val outcome = runCatching {
            MirrorMetadataProcessLock.access(File(args[1]), args.getOrNull(2) ?: "a".repeat(64)) { held ->
                if (args[0] == "hold") {
                    println("HELD"); System.out.flush()
                    while (true) { held(); Thread.sleep(250) }
                }
            }
        }
        println(if (outcome.isSuccess) "ACQUIRED" else "DENIED")
    }
}
