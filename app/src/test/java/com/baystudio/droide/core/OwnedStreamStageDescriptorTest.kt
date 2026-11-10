package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OwnedStreamStageDescriptorTest {
    @Test fun renamedOutputReopensItsOriginalInodeAndPreservesForeignReplacement() = withProject { root ->
        val named = root.resolve("stage")
        val retained = root.resolve("retained")
        AnchoredWorkspaceFiles(root).use { files -> files.createNew("stage").use { opened ->
            opened.write("original".toByteArray())
            val identity = opened.observe().attributes.fileKey()
            Files.move(named.toPath(), retained.toPath())
            named.writeText("foreign!")
            val foreignIdentity = ProjectCopyEvidence.identity(named)
            opened.openWriteChannel().use { channel ->
                assertArrayEquals("original".toByteArray(), contents(channel))
                channel.position(0)
                channel.write(ByteBuffer.wrap("retained".toByteArray()))
                StreamPublicationDurability.platform.forceFile(channel)
                assertEquals(identity, opened.observe().attributes.fileKey())
                assertEquals("retained", retained.readText())
                assertEquals("foreign!", named.readText())
                assertEquals(foreignIdentity, ProjectCopyEvidence.identity(named))
            }
        } }
    }

    @Test fun unlinkedOutputReopensItsHeldInodeWithoutTouchingTheNewNamedFile() = withProject { root ->
        val named = root.resolve("stage")
        AnchoredWorkspaceFiles(root).use { files -> files.createNew("stage").use { opened ->
            opened.write("original".toByteArray())
            val identity = opened.observe().attributes.fileKey()
            Files.delete(named.toPath())
            named.writeText("foreign!")
            val foreignIdentity = ProjectCopyEvidence.identity(named)
            opened.openWriteChannel().use { channel ->
                assertArrayEquals("original".toByteArray(), contents(channel))
                channel.position(0)
                channel.write(ByteBuffer.wrap("retained".toByteArray()))
                StreamPublicationDurability.platform.forceFile(channel)
                assertArrayEquals("retained".toByteArray(), contents(channel))
                assertEquals(identity, opened.observe().attributes.fileKey())
                assertEquals("foreign!", named.readText())
                assertEquals(foreignIdentity, ProjectCopyEvidence.identity(named))
            }
        } }
    }

    @Test fun channelReopenDoesNotTruncateAndHasAnIndependentFilePosition() = withProject { root ->
        AnchoredWorkspaceFiles(root).use { files -> files.createNew("stage").use { opened ->
            opened.write("abcdef".toByteArray())
            opened.openWriteChannel().use { channel ->
                assertEquals(0L, channel.position())
                assertEquals(6L, channel.size())
                opened.write(byteArrayOf('X'.code.toByte()))
                assertEquals(0L, channel.position())
                channel.write(ByteBuffer.wrap(byteArrayOf('Z'.code.toByte())))
                assertEquals(1L, channel.position())
                opened.write(byteArrayOf('Y'.code.toByte()))
                assertEquals(1L, channel.position())
                StreamPublicationDurability.platform.forceFile(channel)
                assertArrayEquals("ZbcdefXY".toByteArray(), contents(channel))
                assertEquals("ZbcdefXY", root.resolve("stage").readText())
            }
        } }
    }

    @Test fun returnedChannelRemainsUsableAfterNativeAndDirectoryCapabilitiesClose() = withProject { root ->
        var channel: FileChannel? = null
        try {
            AnchoredWorkspaceFiles(root).use { files -> files.createNew("stage").use { opened ->
                channel = opened.openWriteChannel()
            } }
            val returned = requireNotNull(channel)
            assertTrue(returned.isOpen)
            returned.write(ByteBuffer.wrap("survives close".toByteArray()))
            StreamPublicationDurability.platform.forceFile(returned)
            assertEquals("survives close", root.resolve("stage").readText())
            assertArrayEquals("survives close".toByteArray(), contents(returned))
        } finally { channel?.close() }
        assertFalse(requireNotNull(channel).isOpen)
    }

    @Test fun readOnlyAndClosedCapabilitiesCannotReopenWritableChannelsOrReusedDescriptors() = withProject { root ->
        root.resolve("read-only-handle").writeText("owner-writable bytes")
        AnchoredWorkspaceFiles(root).use { files ->
            files.openRead("read-only-handle").use { readOnly ->
                assertThrows(IllegalStateException::class.java) { readOnly.openWriteChannel().close() }
                assertEquals("owner-writable bytes", readOnly.readBytes().toString(Charsets.UTF_8))
            }
            val closed = files.createNew("closed")
            closed.write("closed bytes".toByteArray())
            closed.close()
            files.createNew("fresh").use { fresh ->
                fresh.write("fresh bytes".toByteArray())
                assertThrows(IllegalStateException::class.java) { closed.openWriteChannel().close() }
                closed.close()
                assertEquals(11L, fresh.observe().attributes.size())
            }
        }
        assertEquals("owner-writable bytes", root.resolve("read-only-handle").readText())
        assertEquals("closed bytes", root.resolve("closed").readText())
        assertEquals("fresh bytes", root.resolve("fresh").readText())
    }

    @Test fun concurrentCloseAndReopenYieldOnlyTheOriginalChannelOrAClosedError() = withProject { root ->
        val pool = Executors.newFixedThreadPool(2)
        try {
            AnchoredWorkspaceFiles(root).use { files ->
                repeat(20) { index ->
                    val opened = files.createNew("race-$index")
                    var returned: FileChannel? = null
                    try {
                        val payload = "original-$index".toByteArray()
                        opened.write(payload)
                        val start = CountDownLatch(1)
                        val reopen = pool.submit<ChannelOutcome> {
                            check(start.await(5, TimeUnit.SECONDS)) { "Reopen start deadline" }
                            try { ChannelOutcome(opened.openWriteChannel(), null) }
                            catch (failure: Throwable) { ChannelOutcome(null, failure) }
                        }
                        val close = pool.submit {
                            check(start.await(5, TimeUnit.SECONDS)) { "Close start deadline" }
                            opened.close()
                        }
                        start.countDown()
                        val result = reopen.get(5, TimeUnit.SECONDS)
                        returned = result.channel
                        close.get(5, TimeUnit.SECONDS)
                        if (returned != null) {
                            assertNull(result.failure)
                            assertTrue(returned.isOpen)
                            assertArrayEquals(payload, contents(returned))
                        } else {
                            assertTrue("Unexpected reopen failure: ${result.failure}", result.failure is IllegalStateException)
                        }
                        assertArrayEquals(payload, root.resolve("race-$index").readBytes())
                    } finally {
                        returned?.close()
                        opened.close()
                    }
                }
            }
        } finally {
            pool.shutdownNow()
            assertTrue("Descriptor race workers did not exit", pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun privateStageCreationForceCloseAndCollisionFailuresReturnToDescriptorBaseline() = withProject { root ->
        repeat(3) {
            OwnedStreamStage.create(root.resolve("warmup.txt")).use { stage ->
                stage.output.write("warmup".toByteArray())
                stage.force(StreamPublicationDurability.platform)
            }
        }
        val before = descriptorCount()
        repeat(32) { index ->
            val parent = root.resolve("cycle-$index").apply { mkdir() }
            var forced: FileChannel? = null
            var forceCount = 0
            val durability = object : StreamPublicationDurability {
                override fun forceFile(channel: FileChannel) {
                    assertTrue(channel.isOpen)
                    StreamPublicationDurability.platform.forceFile(channel)
                    forced = channel
                    forceCount++
                }
                override fun forceDirectory(directory: File) = StreamPublicationDurability.platform.forceDirectory(directory)
            }
            OwnedStreamStage.create(parent.resolve("target-$index.txt")).use { stage ->
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(stage.file.toPath(), NOFOLLOW_LINKS))
                stage.output.write("payload-$index".toByteArray())
                stage.force(durability)
                assertEquals(stage.identity, ProjectCopyEvidence.identity(stage.file).key)
            }
            assertEquals(1, forceCount)
            assertFalse(requireNotNull(forced).isOpen)
            val plannedName = ".droide-tmp-${UUID.randomUUID()}"
            val collision = parent.resolve(plannedName).apply { writeText("foreign-$index") }
            assertThrows(FileAlreadyExistsException::class.java) {
                OwnedStreamStage.create(parent.resolve("target-$index.txt"), plannedName).close()
            }
            assertEquals("foreign-$index", collision.readText())
        }
        assertEquals("Stage or failed collision leaked descriptors", before, descriptorCount())
    }

    @Test fun ordinaryPostCreationReplacementIsRejectedWhileOutputStillTargetsOriginalInode() = withProject { root ->
        val stage = OwnedStreamStage.create(root.resolve("target.txt"))
        val retained = root.resolve("retained-original")
        val originalIdentity = stage.identity
        val named = stage.file
        try {
            Files.move(named.toPath(), retained.toPath())
            named.writeText("foreign replacement")
            val foreignIdentity = ProjectCopyEvidence.identity(named)
            assertThrows(IllegalStateException::class.java) { stage.requireOwned() }
            stage.output.write("original output".toByteArray())
            stage.output.flush()
            assertEquals("original output", retained.readText())
            assertEquals(originalIdentity, ProjectCopyEvidence.identity(retained).key)
            assertEquals(foreignIdentity, ProjectCopyEvidence.identity(named))
            assertEquals("foreign replacement", named.readText())
        } finally { stage.close() }
        assertEquals("foreign replacement", named.readText())
        assertEquals("original output", retained.readText())
        assertFalse(root.resolve("target.txt").exists())
    }

    private data class ChannelOutcome(val channel: FileChannel?, val failure: Throwable?)

    private fun contents(channel: FileChannel): ByteArray {
        channel.position(0)
        return Channels.newInputStream(channel).readBytes()
    }

    private fun descriptorCount() = Files.newDirectoryStream(File("/proc/self/fd").toPath()).use { it.count() }

    private inline fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("owned-stage-descriptor-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
