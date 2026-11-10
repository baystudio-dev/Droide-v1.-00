package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Pure filesystem diagnostic only; no Context, substrate, shell, process, VM or network class. */
class LegacyAlpinePendingCleanupTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @Test fun retiredGateFailsExplicitlyWithoutCallbacks() {
        val failure = runCatching { LegacyAlpineRuntimeRetirement.reject() }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
        assertEquals(LegacyAlpineRuntimeRetirement.UNSUPPORTED_MESSAGE, failure?.message)
    }

    @Test fun emptyRootAndHealthyUbuntuDoNotProduceLegacyWarning() = fixture { root ->
        assertNull(LegacyAlpineRuntimeRetirement.pendingCleanupWarning(root))
        put(root, "guest/ubuntu-base-24.04.5/rootfs/usr/bin/bash", "ubuntu")
        put(root, "packages/cli.jq/1.7.1/payload/bin/jq", "ubuntu-jq")
        put(root, "vm/images/external.qcow2", "external-image")
        val before = snapshot(root)
        assertNull(LegacyAlpineRuntimeRetirement.pendingCleanupWarning(root))
        assertEquals(before, snapshot(root))
    }

    @Test fun finalStageAndPreviousGuestsRemainByteExact() = fixture { root ->
        listOf("guest/alpine-3.24.2", "guest/.staging/alpine-3.24.2", "guest/.previous/alpine-3.24.2").forEach { guest ->
            put(root, "$guest/rootfs/sbin/apk", "fake; never executable")
            put(root, "$guest/launch", "broken or historic launcher")
            put(root, "$guest/BOOTSTRAP_LOCK.txt", "corrupt identity")
            assertPendingUnchanged(root)
        }
        put(root, "packages/.trash/local-authority/retained.receipt", "unknown receipt")
        put(root, "vm/profiles/old/overlay.qcow2", "old overlay")
        assertPendingUnchanged(root)
    }

    @Test fun exactUnknownMalformedOversizedAndStagedIntentsAllRemainByteExact() = fixture { root ->
        val directory = "packages/.guest-cleanup"
        val exact = "cli.jq-5b499eb224dc240527c7751cc06ee48a33bdea80eda265c28a45b19664452c56"
        listOf(
            exact to "cli.jq\nAlpine 3.24\n.droide_59b0cd65d55dffa8\n",
            "cli.jq-wrong-digest" to "cli.jq\nAlpine 3.24\n.droide_59b0cd65d55dffa8\n",
            "cli.jq-ubuntu" to "cli.jq\n1.7.1\n.droide_unknown\n",
            "cli.jq-unknown" to "cli.jq\nAlpine 9\nbusybox\n",
            "cli.jq-malformed" to "malformed\u0000bytes\n",
            ".cli.jq-stage" to "uncommitted",
            "unknown-provider" to "keep",
            "cli.jq-oversized" to "x".repeat(70_000),
        ).forEach { (name, value) ->
            put(root, "$directory/$name", value)
            assertPendingUnchanged(root)
        }
    }

    @Test fun symlinkIntentAndAncestorAreNotFollowedOrChanged() = fixture { root ->
        val outside = Files.createTempDirectory("droide-retired-outside-").toFile()
        try {
            put(outside, "sentinel", "outside data")
            val directory = File(root, "packages/.guest-cleanup").apply { mkdirs() }
            Files.createSymbolicLink(File(directory, "cli.jq-link").toPath(), File(outside, "sentinel").toPath())
            val outsideBefore = snapshot(outside)
            assertPendingUnchanged(root)
            assertEquals(outsideBefore, snapshot(outside))
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), outside.toPath())
            assertEquals(LegacyAlpineRuntimeRetirement.PENDING_MESSAGE, LegacyAlpineRuntimeRetirement.pendingCleanupWarning(alias))
            assertEquals(outsideBefore, snapshot(outside))
        } finally { deleteFixture(outside) }
    }

    @Test fun danglingLegacyRootAndWrongTypeParentStayPending() = fixture { root ->
        File(root, "guest").mkdirs()
        Files.createSymbolicLink(File(root, "guest/alpine-3.24.2").toPath(), File(root, "missing").toPath())
        assertPendingUnchanged(root)
        put(root, "packages", "wrong type parent")
        assertPendingUnchanged(root)
    }

    private fun assertPendingUnchanged(root: File) {
        val before = snapshot(root)
        assertEquals(LegacyAlpineRuntimeRetirement.PENDING_MESSAGE, LegacyAlpineRuntimeRetirement.pendingCleanupWarning(root))
        assertEquals(before, snapshot(root))
    }

    private fun put(root: File, name: String, content: String) {
        val file = File(root, name)
        file.parentFile!!.mkdirs()
        file.writeText(content)
    }

    private fun snapshot(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        val result = sortedMapOf<String, String>()
        paths.forEach { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val value = when {
                attributes.isSymbolicLink -> "link:${Files.readSymbolicLink(path)}"
                attributes.isDirectory -> "directory"
                else -> {
                    require(attributes.size() <= 100_000)
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
                }
            }
            result[root.toPath().relativize(path).toString()] = value
            require(result.size <= 128)
        }
        result
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-retired-legacy-").toFile()
        try { block(root) } finally { deleteFixture(root) }
    }

    private fun deleteFixture(root: File) {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
