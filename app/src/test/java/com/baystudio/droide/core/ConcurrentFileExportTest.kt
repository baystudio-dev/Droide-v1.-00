package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ConcurrentFileExportTest {
    private val home = Files.createTempDirectory("concurrent-file-export-").toFile()
    private val root = File(home, "project").apply { mkdir() }
    private val staging = File(home, "capture")
    private val documents = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
    }
    private fun owner(blockers: () -> List<String> = { emptyList() }) =
        ExportSourceAdmission("project", root, documents, {}, knownWriterBlockers = blockers,
            requireEpoch = { "epoch" }, epochOpen = { it == "epoch" })
    private fun source(path: String = "app-debug.apk", bytes: ByteArray = "saved APK bytes".toByteArray()) =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    @After fun cleanup() { home.deleteRecursively() }

    @Test fun existingProcessAndGlobalBusyFlagsDoNotBlockAStableApk() = runBlocking {
        val file = source()
        val admission = owner { listOf("Build/Run/Debug task is active", "Agent task is active") }
        val ticket = admission.ticket(ExportScope(file.name))
        assertTrue(ticket.concurrentFileCapture)
        ExportWriterAdmission.beginWriter(root, "Running or unconfirmed workspace process").use {
            assertTrue(admission.preflight(ticket).canChooseDestination)
            admission.withCapture(ticket, false) { permit ->
                ExportCaptureStore(staging).capture(permit).use { captured ->
                    assertEquals(1L, captured.entries)
                    assertEquals(2L, captured.sourceFilesRead)
                    assertEquals(2 * file.length(), captured.sourceBytesRead)
                    assertArrayEquals(file.readBytes(), captured.data(0).readBytes())
                }
            }
            // Export did not erase the process ticket or invent natural process completion.
            assertEquals(listOf("Running or unconfirmed workspace process"), ExportWriterAdmission.snapshotBlockers(root))
        }
    }

    @Test fun lateBackgroundWriterCanContinueButOtherCaptureRemainsExclusive() = runBlocking {
        val file = source()
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        admission.withCapture(ticket, false) { permit ->
            ExportWriterAdmission.withWriter(root, "Late unrelated process") {
                File(root, "other.txt").writeText("work continues")
            }
            assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
            assertFalse(admission.preflight(ticket).canChooseDestination)
            ExportCaptureStore(staging).capture(permit).close()
        }
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test fun folderAndSymlinkRemainStrictAndNeverUseConcurrentPermit() = runBlocking {
        source()
        Files.createSymbolicLink(File(root, "link.apk").toPath(), File(root, "app-debug.apk").toPath())
        for (scope in listOf("", "link.apk")) {
            val admission = owner()
            val ticket = admission.ticket(ExportScope(scope))
            assertFalse(ticket.concurrentFileCapture)
            ExportWriterAdmission.beginWriter(root, "Running or unconfirmed workspace process").use {
                assertFalse(admission.preflight(ticket).canChooseDestination)
                assertThrows(IllegalStateException::class.java) { runBlocking { admission.withCapture(ticket, true) { fail("unsafe capture") } } }
            }
            assertThrows(IllegalStateException::class.java) { runBlocking { admission.withCapture(ticket, false) { fail("unconfirmed capture") } } }
        }
    }

    @Test fun appMetadataNeverBypassesAnUnregisteredDatabaseOpener() = runBlocking {
        val file = source(".droide/sessions/plugins.sqlite")
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.relativeTo(root).invariantSeparatorsPath))
        assertFalse(ticket.concurrentFileCapture)
        ExportWriterAdmission.beginWriter(root, "Plugin conversation database access").use {
            assertFalse(admission.preflight(ticket).canChooseDestination)
            assertThrows(IllegalStateException::class.java) { runBlocking { admission.withCapture(ticket, true) { fail("cold-open race") } } }
        }
        admission.withCapture(ticket, true) {
            assertThrows(ExportCaptureBusyException::class.java) {
                ExportWriterAdmission.withWriter(root, "Plugin conversation database access") { fail("database opener entered") }
            }
        }
        Unit
    }

    @Test fun sameLengthRewriteWithRestoredMtimeCannotSealMixedBytes() = runBlocking {
        val file = source(bytes = ByteArray(1024 * 1024) { 65 })
        val modified = Files.getLastModifiedTime(file.toPath())
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        var changed = false
        val control = ExportControl(onProgress = { progress ->
            if (!changed && progress.bytes > 0L) {
                changed = true
                RandomAccessFile(file, "rw").use { it.seek(0); it.write(ByteArray(1024) { 66 }) }
                Files.setLastModifiedTime(file.toPath(), modified)
            }
        })
        val failure = assertThrows(ExportFailure::class.java) { runBlocking {
            admission.withCapture(ticket, false) { ExportCaptureStore(staging).capture(it, control) }
        } }
        assertEquals("SourceChanged", failure.category)
        assertTrue(changed)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun sameBytesAtomicReplacementIsNotMistakenForTheSelectedInode() = runBlocking {
        val file = source(bytes = ByteArray(512 * 1024) { 42 })
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        var replaced = false
        val control = ExportControl(onProgress = {
            if (!replaced) {
                replaced = true
                val replacement = File(root, "replacement").apply { writeBytes(file.readBytes()) }
                Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
            }
        })
        assertThrows(Exception::class.java) { runBlocking {
            admission.withCapture(ticket, false) { ExportCaptureStore(staging).capture(it, control) }
        } }
        assertTrue(replaced)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun sealedBytesDoNotFollowLaterBuildOutputRewrites() = runBlocking {
        val file = source()
        val expected = file.readBytes()
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        val capture = admission.withCapture(ticket, false) { ExportCaptureStore(staging).capture(it) }
        capture.use {
            file.writeText("new build output")
            assertArrayEquals(expected, it.data(0).readBytes())
            assertTrue(ExportWriterAdmission.snapshotBlockers(root).isEmpty())
        }
    }

    @Test fun repositoryMutationWaitsUntilFileCaptureReleasesItsMutex() = runBlocking {
        withTimeout(5000) {
            source()
            val admission = owner()
            val ticket = admission.ticket(ExportScope("app-debug.apk"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var wrote = false
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withCapture(ticket, false) { entered.complete(Unit); release.await() }
            }
            entered.await()
            val write = launch(start = CoroutineStart.UNDISPATCHED) {
                WorkspaceFileMutationLocks.withRoot(root) { wrote = true }
            }
            assertFalse(wrote)
            release.complete(Unit)
            capture.join(); write.join()
            assertTrue(wrote)
        }
    }

    @Test fun zeroByteFilePreservesNoDescriptorReadAndStillValidates() = runBlocking {
        val file = source(bytes = byteArrayOf())
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        admission.withCapture(ticket, false) {
            ExportCaptureStore(staging).capture(it).use { captured ->
                assertEquals(0L, captured.sourceFilesRead)
                assertEquals(0L, captured.bytes)
            }
        }
    }

    @Test fun captureCancellationReleasesFenceAndRemovesUnsealedBytes() = runBlocking {
        val file = source(bytes = ByteArray(512 * 1024))
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        val failure = assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking {
            admission.withCapture(ticket, false) { permit ->
                ExportCaptureStore(staging).capture(permit, ExportControl(onProgress = { throw kotlinx.coroutines.CancellationException("cancel") }))
            }
        } }
        assertEquals("cancel", failure.message)
        assertTrue(ExportWriterAdmission.snapshotBlockers(root).isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun endlesslyGrowingInputIsRejectedAfterOneExtraByte() {
        var observed = 0L
        val input = object : java.io.InputStream() {
            override fun read(): Int { observed++; return 65 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(bytes, offset, offset + length, 65.toByte())
                observed += length
                return length
            }
        }
        val failure = assertThrows(ExportFailure::class.java) {
            ExportStreams.copyBounded(input, null, ExportControl(), 1024)
        }
        assertEquals("SourceChanged", failure.category)
        assertEquals(1025L, observed)
    }

    @Test fun captureStampMustMatchTicketInodeNotOnlyTwoPathObservations() {
        val selected = source("selected.apk")
        val replacement = source("replacement.apk")
        val expected = ExportFileStamp.read(selected.toPath()).identity
        val permit = object : VerifiedFileExportCapturePermit {
            override val root = this@ConcurrentFileExportTest.root
            override val scope = ExportScope(replacement.name)
            override val verifySingleFile = true
            override val selectedFileIdentity = expected
            override val stabilityAssumptions = emptyList<String>()
            override fun validate() = Unit
        }
        val failure = assertThrows(ExportFailure::class.java) { ExportCaptureStore(staging).capture(permit) }
        assertEquals("ScopeIdentityChanged", failure.category)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun firstTraversalCannotReplaceTicketMetadataWithAnotherInode() = runBlocking {
        val file = source()
        val replacement = source("replacement.apk", file.readBytes())
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        var entries = 0
        val failure = assertThrows(ExportFailure::class.java) { runBlocking {
            admission.withCapture(ticket, false) { original ->
                val bound = original as VerifiedFileExportCapturePermit
                val permit = object : VerifiedFileExportCapturePermit, ExportEntryAdmissionPermit {
                    override val root = original.root
                    override val scope = original.scope
                    override val stabilityAssumptions = original.stabilityAssumptions
                    override val verifySingleFile = true
                    override val selectedFileIdentity = bound.selectedFileIdentity
                    override fun validate() = original.validate()
                    override fun requireEntry(relativePath: String) {
                        if (++entries == 2) Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING)
                    }
                }
                ExportCaptureStore(staging).capture(permit)
            }
        } }
        assertEquals("SourceChanged", failure.category)
        assertEquals(2, entries)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun linkedSourceRecoveryStillBlocksSingleFile() = runBlocking {
        val file = source()
        val admission = ExportSourceAdmission("project", root, documents, {},
            requireSourceSafe = { throw IllegalStateException("Pending linked recovery") },
            requireEpoch = { "epoch" }, epochOpen = { it == "epoch" })
        val ticket = admission.ticket(ExportScope(file.name))
        assertThrows(IllegalStateException::class.java) { runBlocking { admission.preflight(ticket) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { admission.withCapture(ticket, false) { fail("recovery bypass") } } }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationDuringVerificationCannotSealPartialCapture() = runBlocking {
        val file = source(bytes = ByteArray(1024 * 1024) { 33 })
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        var copied = false
        var checksAfterCopy = 0
        val control = ExportControl(
            onProgress = { if (it.entries == 1L) copied = true },
            checkCancelled = {
                if (copied && ++checksAfterCopy >= 3) throw kotlinx.coroutines.CancellationException("verification cancelled")
            },
        )
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking {
            admission.withCapture(ticket, false) { ExportCaptureStore(staging).capture(it, control) }
        } }
        assertTrue(copied)
        assertEquals(3, checksAfterCopy)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertTrue(ExportWriterAdmission.snapshotBlockers(root).isEmpty())
    }

    @Test fun preEpochFractionalMtimeUsesNormalizedPositiveNanoseconds() = runBlocking {
        val file = source()
        val time = java.time.Instant.ofEpochSecond(-1, 123_456_789)
        Files.setLastModifiedTime(file.toPath(), java.nio.file.attribute.FileTime.from(time))
        val normalized = java.nio.file.attribute.FileTime.from(time).toInstant()
        assertEquals(-1L, normalized.epochSecond)
        assertEquals(123_456_789, normalized.nano)
        // Some host filesystems clamp pre-epoch times. Capture must agree with the actual saved
        // metadata, while signed fractional normalization itself stays exact above.
        val actual = Files.getLastModifiedTime(file.toPath()).toInstant()
        val stamp = ExportFileStamp.read(file.toPath())
        assertEquals(actual.epochSecond, stamp.modifiedSeconds)
        assertEquals(actual.nano.toLong(), stamp.modifiedNanos)
        val admission = owner()
        val ticket = admission.ticket(ExportScope(file.name))
        admission.withCapture(ticket, false) { ExportCaptureStore(staging).capture(it).close() }
    }

    @Test fun nanosecondChangeTimeCannotBeRoundedAway() {
        val stamp = ExportFileStamp(1, 2, 3, 0x8000, 1, 1, 7, 2, 9, 10)
        assertNotEquals(stamp, stamp.copy(changedNanos = 11))
        assertNotEquals(stamp, stamp.copy(modifiedNanos = 3))
    }
}
