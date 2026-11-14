package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Uses the production filesystem JNI; run with its host library on java.library.path. */
class UbuntuRuntimeFilesystemTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("droide runtime ").toFile().canonicalFile
        try { test(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun sharedMemoryIsPrivateToTheAppAndReusedWithoutRemovingLiveObjects() = fixture { root ->
        val first = UbuntuRuntimeFilesystem.bindings(root) { true }
        assertEquals(listOf("-b", "${root.path}/runtime-shm:/dev/shm", "-b", "/dev/urandom:/dev/random"), first)
        val memory = root.resolve("runtime-shm")
        val live = memory.resolve("sem.running-worker").apply { writeText("owned by a live process") }
        val identity = Files.readAttributes(memory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        assertEquals(first, UbuntuRuntimeFilesystem.bindings(root) { true })
        assertEquals(identity, Files.readAttributes(memory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals("owned by a live process", live.readText())
        assertFalse(memory.toPath().startsWith(root.resolve("guest").toPath()))
    }

    @Test fun missingDescriptorAliasesAreMappedWithoutReplacingExistingHostAliases() = fixture { root ->
        val argv = UbuntuRuntimeFilesystem.bindings(root) { it == "/dev/stdout" }
        assertTrue(argv.contains("/proc/self/fd:/dev/fd"))
        assertTrue(argv.contains("/proc/self/fd/0:/dev/stdin"))
        assertTrue(argv.contains("/proc/self/fd/2:/dev/stderr"))
        assertFalse(argv.contains("/proc/self/fd/1:/dev/stdout"))
        assertEquals(listOf("-b", "-b", "-b", "-b", "-b"), argv.filter { it == "-b" })
    }

    @Test fun collidingFileIsPreservedAndNeverUsedAsSharedMemory() = fixture { root ->
        val collision = root.resolve("runtime-shm").apply { writeText("preserve") }
        assertThrows(IllegalStateException::class.java) { UbuntuRuntimeFilesystem.bindings(root) }
        assertEquals("preserve", collision.readText())
    }

    @Test fun symlinkCannotRedirectTheSharedMemoryBind() = fixture { root ->
        val outside = Files.createTempDirectory("droide-shm-outside-").toFile()
        try {
            val sentinel = outside.resolve("sentinel").apply { writeText("keep") }
            Files.createSymbolicLink(root.resolve("runtime-shm").toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { UbuntuRuntimeFilesystem.bindings(root) }
            assertEquals("keep", sentinel.readText())
            assertEquals(listOf("sentinel"), outside.listFiles()!!.map { it.name })
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }
}
