package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure fake owners and temporary discovery files. Never starts a process or a socket. */
class CliEndpointPublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 15_000) fun lateFinallyFromSameWorkspaceCannotDeleteTheNewGeneration() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        host.prepareAndPublish(a)
        val b = host.session("workspace")
        assertNotEquals(a.file.stagingFile, b.file.stagingFile)
        host.activate(b)
        assertTrue(a.closed)
        assertFalse(a.file.endpointFile.exists())
        host.prepareAndPublish(b)

        host.workerFinally(a)
        host.workerFinally(a)

        assertEquals(b.payload, b.file.endpointFile.readText())
        assertEquals(listOf(a), host.removed)
        assertFalse(a.file.stagingFile.exists())
    }

    @Test(timeout = 15_000) fun differentWorkspaceActivationWithdrawsThePriorFileBeforeNewPublication() {
        val host = Host(temporary.root)
        val a = host.session("A")
        host.activate(a)
        host.prepareAndPublish(a)
        val b = host.session("B")

        host.activate(b)

        assertFalse(a.file.endpointFile.exists())
        assertFalse(b.file.endpointFile.exists())
        assertSame(b, host.active)
        host.prepareAndPublish(b)
        host.workerFinally(a)
        assertFalse(a.file.endpointFile.exists())
        assertEquals(b.payload, b.file.endpointFile.readText())
        assertEquals(listOf("publish:A", "remove:A", "publish:B"), host.events)
    }

    @Test(timeout = 15_000) fun closedBeforePublicationCannotPublishOrRemoveAnUnownedStaleEndpoint() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        a.file.prepare(a.socketName)
        a.file.endpointFile.writeText("previous process")
        host.close(a)

        assertFalse(host.publication.publish(a))
        host.workerFinally(a)

        assertEquals("previous process", a.file.endpointFile.readText())
        assertFalse(a.file.stagingFile.exists())
        assertTrue(host.removed.isEmpty())
    }

    @Test(timeout = 15_000) fun obsoleteWorkerCannotPublishLateAndCleansOnlyItsOwnStaging() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        a.file.prepare(a.socketName)
        val b = host.session("workspace")
        host.activate(b)
        b.file.prepare(b.socketName)

        assertFalse(host.publication.publish(a))
        host.workerFinally(a)

        assertFalse(a.file.stagingFile.exists())
        assertTrue(b.file.stagingFile.exists())
        assertTrue(host.publication.publish(b))
        assertEquals(b.payload, b.file.endpointFile.readText())
    }

    @Test(timeout = 15_000) fun neverPublishedOwnerCleanupDoesNotDeleteAFileFromAPriorProcess() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        a.file.endpointFile.parentFile.mkdirs()
        a.file.endpointFile.writeText("previous process")

        host.close(a)
        host.workerFinally(a)
        host.close(a)

        assertEquals("previous process", a.file.endpointFile.readText())
        assertTrue(host.removed.isEmpty())
    }

    @Test(timeout = 15_000) fun failedReplacementGrantsNoOwnershipAndPreservesTheExistingFile() {
        val host = Host(temporary.root)
        val b = host.session("workspace")
        host.activate(b)
        b.file.prepare(b.socketName)
        b.file.endpointFile.writeText("previous process")
        host.failPublish = b

        assertTrue(runCatching { host.publication.publish(b) }.exceptionOrNull() is IOException)
        host.workerFinally(b)

        assertTrue(host.removed.isEmpty())
        assertFalse(b.file.stagingFile.exists())
        assertEquals("previous process", b.file.endpointFile.readText())

        val c = host.session("workspace")
        host.activate(c)
        host.prepareAndPublish(c)
        host.workerFinally(b)
        assertEquals(c.payload, c.file.endpointFile.readText())
    }

    @Test(timeout = 15_000) fun actualAtomicMoveFailureNeverUnlinksTheDestinationOrGrantsOwnership() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        a.file.prepare(a.socketName)
        // A nonempty directory cannot be atomically replaced by a regular staging file.
        assertTrue(a.file.endpointFile.mkdir())
        val protected = File(a.file.endpointFile, "keep")
        protected.writeText("untouched")

        assertTrue(runCatching { host.publication.publish(a) }.exceptionOrNull() is IOException)
        host.workerFinally(a)

        assertEquals("untouched", protected.readText())
        assertTrue(host.removed.isEmpty())
        assertFalse(a.file.stagingFile.exists())
    }

    @Test(timeout = 15_000) fun immediateCloseAndDuplicateFinallyWithdrawExactlyOnce() {
        val host = Host(temporary.root)
        val a = host.session("workspace")
        host.activate(a)
        host.prepareAndPublish(a)

        host.close(a)
        host.close(a)
        host.workerFinally(a)
        host.workerFinally(a)

        assertEquals(listOf(a), host.removed)
        assertFalse(a.file.endpointFile.exists())
        assertFalse(a.file.stagingFile.exists())
    }

    @Test(timeout = 15_000) fun failedWithdrawalRetainsTheOnlyOwnerAndRefusesAnotherWorkspace() {
        val host = Host(temporary.root)
        val a = host.session("A")
        host.activate(a)
        host.prepareAndPublish(a)
        val b = host.session("B")
        host.failRemove = a

        assertTrue(runCatching { host.activate(b) }.exceptionOrNull() is IOException)
        assertSame(a, host.active)
        assertTrue(a.closed)
        assertFalse(host.publication.publish(b))
        assertTrue(a.file.endpointFile.exists())
        assertFalse(b.file.endpointFile.exists())

        // A new explicit activation may perform one retry; there is no background loop.
        host.failRemove = null
        host.activate(b)
        assertFalse(a.file.endpointFile.exists())
        host.prepareAndPublish(b)
        host.workerFinally(a)
        assertEquals(b.payload, b.file.endpointFile.readText())
        assertEquals(listOf(a), host.removed)
    }

    @Test(timeout = 15_000) fun publicationSlotRefusesAnUnwithdrawnOwnerEvenIfCurrentCallbackIsWrong() {
        val a = Any()
        val b = Any()
        val replaced = mutableListOf<Any>()
        val publication = CliEndpointPublication<Any>(
            isCurrent = { true }, replace = { replaced += it }, remove = {},
        )
        assertTrue(publication.publish(a))

        assertTrue(runCatching { publication.publish(b) }.exceptionOrNull() is IllegalStateException)

        assertEquals(listOf(a), replaced)
        publication.withdraw(a)
        assertTrue(publication.publish(b))
        assertEquals(listOf(a, b), replaced)
    }

    @Test(timeout = 15_000) fun stagingAndPublishedFileHaveOwnerOnlyPermissionsAndUnchangedWirePayload() {
        val endpoint = CliEndpointFile(temporary.root, "workspace-1")
        endpoint.prepare("fake-socket")
        val expected = PosixFilePermissions.fromString("rw-------")
        assertEquals(expected, Files.getPosixFilePermissions(endpoint.stagingFile.toPath(), NOFOLLOW_LINKS))

        endpoint.publishPrepared()

        assertEquals(expected, Files.getPosixFilePermissions(endpoint.endpointFile.toPath(), NOFOLLOW_LINKS))
        assertEquals("DROIDE_CLI_ENDPOINT_V1\nfake-socket\nworkspace-1\n", endpoint.endpointFile.readText())
        assertFalse(endpoint.stagingFile.exists())
    }

    @Test(timeout = 15_000) fun stagingSymlinkIsNotFollowedAndCleanupRemovesOnlyTheLink() {
        val endpoint = CliEndpointFile(temporary.root, "workspace")
        endpoint.stagingFile.parentFile.mkdirs()
        val protected = temporary.newFile("protected")
        protected.writeText("untouched")
        Files.createSymbolicLink(endpoint.stagingFile.toPath(), protected.toPath())

        assertTrue(runCatching { endpoint.prepare("fake-socket") }.isFailure)
        endpoint.discardStaging()

        assertEquals("untouched", protected.readText())
        assertFalse(Files.exists(endpoint.stagingFile.toPath(), NOFOLLOW_LINKS))
    }

    @Test(timeout = 15_000) fun endpointReplacementAndWithdrawalNeverFollowTheEndpointSymlink() {
        val endpoint = CliEndpointFile(temporary.root, "workspace")
        endpoint.prepare("fake-socket")
        val protected = temporary.newFile("protected")
        protected.writeText("untouched")
        Files.createSymbolicLink(endpoint.endpointFile.toPath(), protected.toPath())

        endpoint.publishPrepared()

        assertFalse(Files.isSymbolicLink(endpoint.endpointFile.toPath()))
        assertEquals("untouched", protected.readText())
        Files.delete(endpoint.endpointFile.toPath())
        Files.createSymbolicLink(endpoint.endpointFile.toPath(), protected.toPath())
        endpoint.withdraw()
        assertFalse(Files.exists(endpoint.endpointFile.toPath(), NOFOLLOW_LINKS))
        assertEquals("untouched", protected.readText())
    }

    @Test(timeout = 15_000) fun discoverySymlinkAndDotWorkspaceNamesCannotEscapeTheOwnedDirectory() {
        val filesDir = temporary.newFolder("app-files")
        val outside = temporary.newFolder("outside")
        val cliDir = File(filesDir, "cli")
        assertTrue(cliDir.mkdir())
        Files.createSymbolicLink(File(cliDir, "workspace").toPath(), outside.toPath())
        val endpoint = CliEndpointFile(filesDir, "workspace")

        assertTrue(runCatching { endpoint.prepare("fake-socket") }.isFailure)
        assertTrue(outside.listFiles()!!.isEmpty())
        assertTrue(runCatching { CliEndpointFile(filesDir, ".") }.isFailure)
        assertTrue(runCatching { CliEndpointFile(filesDir, "..") }.isFailure)
    }

    private class Owner(val file: CliEndpointFile, val workspaceId: String, val socketName: String) {
        var closed = false
        val payload: String get() = "DROIDE_CLI_ENDPOINT_V1\n$socketName\n$workspaceId\n"
    }

    private class Host(private val root: File) {
        var active: Owner? = null
        var failPublish: Owner? = null
        var failRemove: Owner? = null
        val removed = mutableListOf<Owner>()
        val events = mutableListOf<String>()
        private var generation = 0
        val publication: CliEndpointPublication<Owner> = CliEndpointPublication(
            isCurrent = { active === it && !it.closed },
            replace = {
                assertTrue(Thread.holdsLock(publicationMonitor()))
                if (failPublish === it) throw IOException("injected replacement failure")
                it.file.publishPrepared()
                events += "publish:${it.workspaceId}"
            },
            remove = {
                assertTrue(Thread.holdsLock(publicationMonitor()))
                if (failRemove === it) throw IOException("injected withdrawal failure")
                it.file.withdraw()
                removed += it
                events += "remove:${it.workspaceId}"
            },
        )

        private fun publicationMonitor(): Any = publication.monitor

        fun session(workspaceId: String) = Owner(CliEndpointFile(root, workspaceId), workspaceId, "fake-${++generation}")

        fun activate(next: Owner) = synchronized(publication.monitor) {
            active?.closed = true
            publication.withdrawPrevious()
            active = next
        }

        fun prepareAndPublish(owner: Owner) {
            assertFalse(Thread.holdsLock(publication.monitor))
            owner.file.prepare(owner.socketName)
            assertTrue(publication.publish(owner))
        }

        fun close(owner: Owner) = synchronized(publication.monitor) {
            owner.closed = true
            if (active === owner) active = null
            publication.withdraw(owner)
        }

        fun workerFinally(owner: Owner) {
            publication.withdraw(owner)
            owner.file.discardStaging()
        }
    }
}
