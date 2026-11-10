package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

class OwnedStreamStageCloseTest {
    private val payload = "original staged payload".toByteArray()

    @Test fun verifiedClosePreservesTheNamedStageAndClosesItsDescriptor() = fixture { root ->
        OwnedStreamStage.create(root.resolve("target.bin")).use { stage ->
            stage.output.write(payload)
            stage.force(StreamPublicationDurability.platform)
            val before = ProjectCopyEvidence.identity(stage.file)
            assertNull(stage.closeObservation)
            stage.publicationVerified()
            stage.close()
            val observed = requireNotNull(stage.closeObservation)
            assertEquals(StagePathState.PRESENT, observed.state)
            assertEquals(payload.size.toLong(), observed.bytes)
            assertEquals("Automatic cleanup withheld", observed.detail)
            assertEquals(before, ProjectCopyEvidence.identity(stage.file))
            assertArrayEquals(payload, stage.file.readBytes())
            assertThrows(IOException::class.java) { stage.output.write(1) }
            stage.close()
            assertSame(observed, stage.closeObservation)
            assertFalse(root.resolve("target.bin").exists())
        }
    }

    @Test fun nativePublicationConsumesTheNameAndClosePreservesCanonicalBytes() = fixture { root ->
        val target = root.resolve("target.bin")
        val stage = OwnedStreamStage.create(target)
        try {
            stage.output.write(payload)
            stage.force(StreamPublicationDurability.platform)
            NewProjectFilePublisher.native().publish(stage.file, target)
            stage.publicationVerified()
        } finally { stage.close() }
        assertEquals(StagePathState.ABSENT_AT_RECORDED_PATH, requireNotNull(stage.closeObservation).state)
        assertFalse(stage.file.exists())
        assertArrayEquals(payload, target.readBytes())
        assertEquals(stage.identity, ProjectCopyEvidence.identity(target).key)
        assertThrows(IOException::class.java) { stage.output.write(1) }
    }

    @Test fun foreignReplacementSurvivesCloseWithBothIdentitiesAndContentsIntact() = fixture { root ->
        OwnedStreamStage.create(root.resolve("target.bin")).use { stage ->
            stage.output.write(payload)
            stage.output.flush()
            val retained = root.resolve("retained-original")
            Files.move(stage.file.toPath(), retained.toPath())
            stage.file.writeText("foreign replacement")
            val foreign = ProjectCopyEvidence.identity(stage.file)
            stage.close()
            val observed = requireNotNull(stage.closeObservation)
            assertEquals(StagePathState.FOREIGN, observed.state)
            assertEquals(foreign.size, observed.bytes)
            assertEquals(foreign, ProjectCopyEvidence.identity(stage.file))
            assertEquals("foreign replacement", stage.file.readText())
            assertEquals(stage.identity, ProjectCopyEvidence.identity(retained).key)
            assertArrayEquals(payload, retained.readBytes())
            assertThrows(IOException::class.java) { stage.output.write(1) }
        }
    }

    @Test fun foreignLinksAndDirectoriesAreNeitherFollowedNorRemoved() {
        for (kind in listOf("symlink", "empty-directory", "populated-directory")) fixture { root ->
            val outside = Files.createTempFile("close-stage-outside-", ".bin").toFile()
            try {
                outside.writeText("outside private bytes")
                val outsideIdentity = ProjectCopyEvidence.identity(outside)
                OwnedStreamStage.create(root.resolve("target.bin")).use { stage ->
                    stage.output.write(payload)
                    stage.output.flush()
                    val retained = root.resolve("retained-original")
                    Files.move(stage.file.toPath(), retained.toPath())
                    if (kind == "symlink") Files.createSymbolicLink(stage.file.toPath(), outside.toPath())
                    else {
                        assertTrue(stage.file.mkdir())
                        if (kind == "populated-directory") stage.file.resolve("keep.txt").writeText("directory bytes")
                    }
                    stage.close()
                    assertEquals(StagePathState.FOREIGN, requireNotNull(stage.closeObservation).state)
                    assertNull(stage.closeObservation!!.bytes)
                    if (kind == "symlink") assertTrue(Files.isSymbolicLink(stage.file.toPath()))
                    else {
                        assertTrue(stage.file.isDirectory)
                        if (kind == "populated-directory") assertEquals("directory bytes", stage.file.resolve("keep.txt").readText())
                        else assertTrue(stage.file.listFiles()!!.isEmpty())
                    }
                    assertEquals(outsideIdentity, ProjectCopyEvidence.identity(outside))
                    assertEquals("outside private bytes", outside.readText())
                    assertArrayEquals(payload, retained.readBytes())
                    assertEquals(stage.identity, ProjectCopyEvidence.identity(retained).key)
                    assertThrows(IOException::class.java) { stage.output.write(1) }
                }
            } finally { outside.delete() }
        }
    }

    @Test fun movedParentAndReplacementParentProduceAnUnverifiableLocationWithoutDeletion() = fixture { root ->
        val parent = root.resolve("parent").apply { mkdir() }
        OwnedStreamStage.create(parent.resolve("target.bin")).use { stage ->
            stage.output.write(payload)
            stage.output.flush()
            val retainedParent = root.resolve("retained-parent")
            Files.move(parent.toPath(), retainedParent.toPath())
            assertTrue(parent.mkdir())
            stage.file.writeText("new parent bytes")
            val foreign = ProjectCopyEvidence.identity(stage.file)
            stage.close()
            assertEquals(StagePathState.UNVERIFIABLE, requireNotNull(stage.closeObservation).state)
            assertNotNull(stage.closeObservation!!.detail)
            assertEquals(foreign, ProjectCopyEvidence.identity(stage.file))
            assertEquals("new parent bytes", stage.file.readText())
            val retained = retainedParent.resolve(stage.file.name)
            assertEquals(stage.identity, ProjectCopyEvidence.identity(retained).key)
            assertArrayEquals(payload, retained.readBytes())
            assertThrows(IOException::class.java) { stage.output.write(1) }
        }
    }

    @Test fun closeSnapshotIsStableWithoutReobservingAChangedOrUnreadablePath() = fixture { root ->
        OwnedStreamStage.create(root.resolve("target.bin")).use { stage ->
            stage.output.write(payload)
            stage.close()
            val observed = requireNotNull(stage.closeObservation)
            val retained = root.resolve("retained-original")
            Files.move(stage.file.toPath(), retained.toPath())
            stage.file.writeText("later foreign bytes")
            val permissions = Files.getPosixFilePermissions(root.toPath())
            try {
                Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("---------"))
                repeat(100) {
                    assertSame(observed, stage.closeObservation)
                    stage.close()
                    assertSame(observed, stage.closeObservation)
                }
            } finally { Files.setPosixFilePermissions(root.toPath(), permissions) }
            assertEquals(StagePathState.PRESENT, observed.state)
            assertArrayEquals(payload, retained.readBytes())
            assertEquals("later foreign bytes", stage.file.readText())
        }
    }

    @Test fun unreadableParentIsReportedWithoutMaskingTheOriginalFailureOrLeakingTheChannel() = fixture { root ->
        val parent = root.resolve("parent").apply { mkdir() }
        val stage = OwnedStreamStage.create(parent.resolve("target.bin"))
        val original = IOException("original copy failure")
        val permissions = Files.getPosixFilePermissions(parent.toPath())
        try {
            stage.output.write(payload)
            stage.output.flush()
            Files.setPosixFilePermissions(parent.toPath(), PosixFilePermissions.fromString("---------"))
            val actual = runCatching { stage.use { throw original } }.exceptionOrNull()
            assertSame(original, actual)
            assertEquals("Requires ordinary nonprivileged permission enforcement", StagePathState.UNVERIFIABLE,
                requireNotNull(stage.closeObservation).state)
            assertNotNull(stage.closeObservation!!.detail)
            assertThrows(IOException::class.java) { stage.output.write(1) }
        } finally {
            Files.setPosixFilePermissions(parent.toPath(), permissions)
            stage.close()
        }
        assertArrayEquals(payload, stage.file.readBytes())
        assertEquals(stage.identity, ProjectCopyEvidence.identity(stage.file).key)
    }

    private inline fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("owned-stage-close-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
