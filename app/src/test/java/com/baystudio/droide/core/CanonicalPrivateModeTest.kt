package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CanonicalPrivateModeTest {
    private val context = EmptyCoroutineContext
    private fun mode(file: File) = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095
    private fun private(file: File) { Files.setAttribute(file.toPath(), "unix:mode", 384) }

    @Test fun nativeForceRetainsOwnerOnlyAccess() = CanonicalJournalFixture().use { f ->
        AnchoredWorkspaceFiles(f.custody).use { parent ->
            parent.createNew("private").use { output ->
                output.write("private content".toByteArray()); output.force(384)
                assertEquals(384, output.permissionMode())
            }
        }
        assertEquals(384, mode(File(f.custody, "private")))
        assertEquals("private content", File(f.custody, "private").readText())
    }

    @Test fun snapshotPreservesPrivatePermissionsAndIndependentBytes() = CanonicalJournalFixture().use { f ->
        private(f.source); val before = f.before()
        AnchoredWorkspaceFiles(f.source.parentFile).use { source -> AnchoredWorkspaceFiles(f.custody).use { store ->
            val copy = CanonicalFileSnapshot(f.bytes.size.toLong()).capture(source, f.source.name, before, store, "private-copy", context)
            assertEquals(384, copy.copy.mode); assertNotEquals(before.identity, copy.copy.identity)
        } }
        assertEquals(384, mode(f.source)); assertEquals(384, mode(File(f.custody, "private-copy")))
        assertArrayEquals(f.bytes, File(f.custody, "private-copy").readBytes())
    }

    @Test fun privateBeforeVersionIsDurableAndReadableAfterReopening() = CanonicalJournalFixture().use { f ->
        private(f.source)
        val intent = f.journal().admit(f.path, f.before(), f.proposal(), context)
        f.journal().capture(intent.id, context)
        val read = f.journal().read()!!
        assertEquals(384, checkNotNull(read.before).mode); assertEquals(384, read.snapshot!!.mode)
        f.journal().verifySnapshot(intent.id, context)
        assertEquals(384, mode(f.snapshotFile(intent.id))); assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun privateProposalAndStageRemainPrivateAfterCustody() = CanonicalJournalFixture().use { f ->
        val stage = File(f.base, "proposal").apply { writeBytes(f.proposedBytes) }; private(stage)
        val expected = f.before(stage)
        val intent = f.journal().admit(f.path, f.before(), CanonicalFileOperationJournal.Content(expected.size, expected.sha256, 384), context)
        f.journal().capture(intent.id, context)
        AnchoredWorkspaceFiles(f.base).use { f.journal().captureProposal(intent.id, it, stage.name, expected, context) }
        assertEquals(384, f.journal().read()!!.proposalCopy!!.mode)
        assertEquals(384, mode(stage)); assertEquals(384, mode(File(f.custody, intent.proposalName)))
        assertArrayEquals(f.proposedBytes, stage.readBytes()); assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun actualProjectCatalogAtPrivateModeCanBeCapturedWithoutWidening() = runBlocking {
        CanonicalJournalFixture().use { f ->
            FileProjectStore(f.root).save(emptyList())
            val catalog = File(f.root, ".droide/projects.json"); private(catalog)
            val bytes = catalog.readBytes(); val before = f.before(catalog)
            val intent = f.journal().admit(".droide/projects.json", before,
                CanonicalFileOperationJournal.Content(3, CanonicalJournalFixture.sha256("[]\n".toByteArray()), 384), context)
            f.journal().capture(intent.id, context)
            assertEquals(384, f.journal().read()!!.snapshot!!.mode)
            assertEquals(384, mode(catalog)); assertEquals(384, mode(f.snapshotFile(intent.id)))
            assertArrayEquals(bytes, catalog.readBytes()); assertArrayEquals(bytes, f.snapshotFile(intent.id).readBytes())
        }
    }

    @Test fun interruptedPrivateCaptureKeepsSourceAndPartialCopyPrivate() = CanonicalJournalFixture().use { f ->
        private(f.source); val before = f.before(); val failure = IOException("injected after copy")
        val snapshots = CanonicalFileSnapshot(f.bytes.size.toLong()) { at, _ ->
            if (at == CanonicalFileSnapshot.Boundary.COPIED) throw failure
        }
        AnchoredWorkspaceFiles(f.source.parentFile).use { source -> AnchoredWorkspaceFiles(f.custody).use { store ->
            assertSame(failure, runCatching { snapshots.capture(source, f.source.name, before, store, "incomplete", context) }.exceptionOrNull())
        } }
        assertEquals(384, mode(f.source)); assertEquals(384, mode(File(f.custody, "incomplete")))
        assertArrayEquals(f.bytes, f.source.readBytes()); assertArrayEquals(f.bytes, File(f.custody, "incomplete").readBytes())
    }

    @Test fun openWriterCannotMutateAnIndependentPrivateSnapshot() = CanonicalJournalFixture().use { f ->
        private(f.source)
        RandomAccessFile(f.source, "rw").use { writer ->
            val intent = f.journal().admit(f.path, f.before(), f.proposal(), context)
            f.journal().capture(intent.id, context)
            writer.seek(0); writer.write(byteArrayOf(55, 56, 57)); writer.fd.sync()
            assertArrayEquals(f.bytes, f.snapshotFile(intent.id).readBytes())
            assertEquals(384, mode(f.snapshotFile(intent.id)))
            f.journal().verifySnapshot(intent.id, context)
            assertEquals(55, f.source.readBytes()[0].toInt())
        }
    }

    @Test fun unsupportedPermissionModesStillFailWithoutBroadening() = CanonicalJournalFixture().use { f ->
        AnchoredWorkspaceFiles(f.custody).use { store ->
            for (unsupported in listOf(0, 292, 438, 511, 2541, 1517, 1005)) {
                store.createNew("unsupported-$unsupported").use { output ->
                    output.write(byteArrayOf(7)); assertNotNull(runCatching { output.force(unsupported) }.exceptionOrNull())
                    assertEquals(384, output.permissionMode())
                }
                assertArrayEquals(byteArrayOf(7), File(f.custody, "unsupported-$unsupported").readBytes())
            }
        }
    }

    @Test fun sameModeReplacementDoesNotMasqueradeAsTheObservedPrivateFile() = CanonicalJournalFixture().use { f ->
        private(f.source); val expected = f.before()
        val retained = File(f.source.parentFile, "retained"); Files.move(f.source.toPath(), retained.toPath())
        f.source.writeText("new user version"); private(f.source)
        AnchoredWorkspaceFiles(f.source.parentFile).use { source -> AnchoredWorkspaceFiles(f.custody).use { store ->
            assertNotNull(runCatching { CanonicalFileSnapshot(f.bytes.size.toLong()).capture(source, f.source.name, expected, store, "copy", context) }.exceptionOrNull())
        } }
        assertEquals("new user version", f.source.readText()); assertArrayEquals(f.bytes, retained.readBytes())
        assertFalse(File(f.custody, "copy").exists()); assertEquals(384, mode(f.source)); assertEquals(384, mode(retained))
    }
}
