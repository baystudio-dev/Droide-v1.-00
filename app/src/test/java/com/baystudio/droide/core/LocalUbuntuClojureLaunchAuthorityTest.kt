package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuClojureLaunchAuthorityTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("clojure-authority-read-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun boundedRegularTextAndHashAreExact() = fixture { root ->
        val file = File(root, "receipt").apply { writeText("{\"source\":\"λ\"}\n") }
        assertEquals(file.readText(), LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 65_536))
        assertEquals(file.length(), LocalUbuntuClojureLaunchAuthority.measure(file, 65_536).first)
        assertEquals(LocalUbuntuClojurePolicy.sha256(file.readBytes()), LocalUbuntuClojureLaunchAuthority.measure(file, 65_536).second)
    }
    @Test fun sourceBoundRejectsSparseNativeAbove256MiB() = fixture { root ->
        val file = File(root, "grown-native")
        RandomAccessFile(file, "rw").use { it.setLength(268_435_457) }
        rejects { LocalUbuntuClojureLaunchAuthority.measure(file, 268_435_456) }
    }
    @Test fun authorityBoundsRejectEmptyOversizedNonRegularAndLinks() = fixture { root ->
        val file = File(root, "receipt").apply { writeText("x".repeat(16_385)) }
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 16_384) }
        file.writeText(""); rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 16_384) }
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(root, 16_384) }
        file.writeText("x")
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), file.toPath())
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(link, 16_384) }
    }
    @Test fun heldDescriptorGrowthIsBounded() = fixture { root ->
        val file = File(root, "receipt").apply { writeText("first") }; var calls = 0
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 16_384) {
            if (++calls == 2) file.appendText("growth")
        } }
        assertEquals(2, calls)
    }
    @Test fun heldDescriptorPathReplacementIsRejected() = fixture { root ->
        val file = File(root, "receipt").apply { writeText("first") }; var calls = 0
        rejects { LocalUbuntuClojureLaunchAuthority.measure(file, 16_384) {
            if (++calls == 2) {
                check(file.renameTo(File(root, "old")))
                file.writeText("other")
            }
        } }
    }
    @Test fun cancellationAndInvalidUtf8FailClosed() = fixture { root ->
        val file = File(root, "receipt").apply { writeText("first") }
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 16_384) { throw kotlinx.coroutines.CancellationException("fixture") } }
        file.writeBytes(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        rejects { LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 16_384) }
    }
}
