package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuAptTransactionStorageTest {
    private val id = "1234567890abcdef12345678"
    private class Fixture(val root: File) {
        val storage = UbuntuAptTransactionStorage(root)
        fun write(path: String, text: String = "keep") = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun transaction(id: String) = write("rootfs/var/cache/droide-foundry/$id/archives/pending.deb").parentFile!!.parentFile!!
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide-apt-transactions").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }
    private fun locked(file: File): Process {
        file.parentFile!!.mkdirs(); if (!file.exists()) file.writeText("")
        val process = ProcessBuilder("python3", "-u", "-c",
            "import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX); print('locked',flush=True); sys.stdin.read()", file.path)
            .redirectErrorStream(true).start()
        if (process.inputStream.bufferedReader().readLine() != "locked") { process.destroyForcibly(); error("Native lock fixture failed") }
        return process
    }
    private fun release(process: Process) {
        process.outputStream.close()
        if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(1000, TimeUnit.MILLISECONDS) }
    }

    @Test(timeout = 5000) fun crossProcessInstallLockQueuesAndReleasesWithoutDeletingTheLockFile() = runBlocking {
        fixture { f ->
            val lockFile = File(f.root, ".droide-apt-install.lock")
            val process = locked(lockFile)
            try {
                val waiting = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
                val task = async { f.storage.operation({ waiting.complete(Unit) }) { entered.complete(Unit); "done" } }
                withTimeout(1000) { waiting.await() }; assertFalse(entered.isCompleted)
                release(process); assertEquals("done", withTimeout(2000) { task.await() })
                assertTrue(entered.isCompleted); assertTrue(lockFile.isFile)
            } finally { if (process.isAlive) release(process) }
        }
    }

    @Test(timeout = 5000) fun waitingInstallationCanBeCancelledWithoutAffectingTheOwner() = runBlocking {
        fixture { f ->
            val process = locked(File(f.root, ".droide-apt-install.lock"))
            try {
                val waiting = CompletableDeferred<Unit>(); var entered = false
                val task = launch { f.storage.operation({ waiting.complete(Unit) }) { entered = true } }
                withTimeout(1000) { waiting.await(); task.cancelAndJoin() }
                assertFalse(entered); assertTrue(process.isAlive)
            } finally { release(process) }
        }
    }

    @Test fun abandonedPrivateDownloadsAreRemovedWhilePackagesHomesAndSignedIndexesArePreserved() = runBlocking {
        fixture { f ->
            val cache = f.transaction(id)
            val keep = listOf("rootfs/var/lib/apt/lists/noble_InRelease", "rootfs/var/lib/apt/lists/noble_Packages",
                "rootfs/var/cache/apt/archives/user.deb", "rootfs/root/project/main.py", "rootfs/usr/lib/go/src/runtime.go",
                "rootfs/var/cache/droide-foundry/user-cache/note", "rootfs/var/lib/dpkg/lock-frontend").map { f.write(it) }
            assertEquals(1, f.storage.operation({}) { f.storage.pruneAbandoned() })
            assertFalse(cache.exists()); keep.forEach { assertEquals("keep", it.readText()) }
        }
    }

    @Test(timeout = 5000) fun nativeDpkgLockDefersOrphanPruningUntilTheProcessFinishes() = runBlocking {
        fixture { f ->
            val cache = f.transaction(id); val lockFile = f.write("rootfs/var/lib/dpkg/lock-frontend", "")
            val process = locked(lockFile)
            try {
                assertEquals(0, f.storage.operation({}) { f.storage.pruneAbandoned() }); assertTrue(cache.exists())
                release(process)
                assertEquals(1, f.storage.operation({}) { f.storage.pruneAbandoned() }); assertFalse(cache.exists())
                assertTrue(lockFile.exists())
            } finally { if (process.isAlive) release(process) }
        }
    }

    @Test(timeout = 5000) fun survivingArchiveLockPreservesAnActiveTransactionDuringCleanup() = runBlocking {
        fixture { f ->
            val cache = f.transaction(id); val lockFile = f.write("rootfs/var/cache/droide-foundry/$id/archives/lock", "")
            val process = locked(lockFile)
            try {
                f.storage.cleanup(id); assertTrue(cache.exists())
                assertEquals(0, f.storage.operation({}) { f.storage.pruneAbandoned() })
                release(process); f.storage.cleanup(id); assertFalse(cache.exists())
            } finally { if (process.isAlive) release(process) }
        }
    }

    @Test fun linksInsidePrivateCacheAreUnlinkedWithoutFollowingThem() = runBlocking {
        fixture { f ->
            val cache = f.transaction(id); val keep = f.write("outside/personal.txt")
            Files.createSymbolicLink(File(cache, "external").toPath(), keep.parentFile!!.toPath())
            f.storage.cleanup(id); assertFalse(cache.exists()); assertEquals("keep", keep.readText())
        }
    }

    @Test fun symlinkedTransactionRootsAndInvalidIdsCannotDeleteUserData() = runBlocking {
        fixture { f ->
            val keep = f.write("outside/personal.txt")
            val link = File(f.root, "rootfs/var/cache/droide-foundry/$id").apply { parentFile!!.mkdirs() }
            Files.createSymbolicLink(link.toPath(), keep.parentFile!!.toPath())
            assertEquals(0, f.storage.operation({}) { f.storage.pruneAbandoned() })
            for (key in listOf(id, "../../outside")) {
                try { f.storage.cleanup(key); fail("Unsafe cleanup was accepted") } catch (_: IllegalArgumentException) {}
            }
            assertEquals("keep", keep.readText()); assertTrue(Files.isSymbolicLink(link.toPath()))
        }
    }
}
