package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectRemovalRetentionTest {
    private class CallbackStore(private val delegate: FileProjectStore) : ProjectStore by delegate {
        var afterCommit: (suspend (List<DroideProject>) -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            delegate.save(all)
            val callback = afterCommit
            afterCommit = null
            callback?.invoke(all)
        }
    }

    private class Fixture(
        val app: File,
        val store: CallbackStore,
        val manager: ProjectManager,
        val project: DroideProject,
    ) {
        val root = File(project.rootPath)
        val recovery = File(root.parentFile, ".delete-${project.id}")
        val draft = File(root, "draft.txt")
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val records = manager.projects.value.toList()
        val activeId = manager.activeId.value
        val defaultSource = File(app, "projects/default/keep.txt")

        suspend fun assertRemoved() {
            val expected = records.filterNot { it.id == project.id }
            assertEquals(expected, manager.projects.value)
            assertEquals(expected, store.load())
            assertEquals(activeId, manager.activeId.value)
            assertEquals(activeId, store.loadActiveId())
            assertFalse(manager.recentProjects.value.any { it.id == project.id })
            assertFalse(root.exists())
            assertEquals("unrelated Default work", defaultSource.readText())
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-removal-retention-").toFile()
        try {
            val store = CallbackStore(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Retained project")
            val f = Fixture(app, store, manager, project)
            f.defaultSource.writeText("unrelated Default work")
            f.draft.writeText("unpublished editor and CLI work")
            check(File(f.root, "nested").mkdir())
            File(f.root, "nested/source.bin").writeBytes(f.payload)
            block(f)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private fun key(file: File) =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    @Test fun committedRemovalKeepsTheExactSourceTreeOutsideTheRegisteredWorkspace() = fixture { f ->
        val treeKey = key(f.root)
        val draftKey = key(f.draft)
        val binaryKey = key(File(f.root, "nested/source.bin"))

        val outcome = f.manager.deleteWorkspace(f.project.id)

        f.assertRemoved()
        assertEquals(f.project.id, outcome?.projectId)
        assertEquals(f.recovery.path, outcome?.recoveryPath)
        assertEquals(treeKey, key(f.recovery))
        assertEquals(draftKey, key(File(f.recovery, "draft.txt")))
        assertEquals(binaryKey, key(File(f.recovery, "nested/source.bin")))
        assertEquals("unpublished editor and CLI work", File(f.recovery, "draft.txt").readText())
        assertArrayEquals(f.payload, File(f.recovery, "nested/source.bin").readBytes())
    }

    @Test fun replacementAtTheRecoveryNameAfterCommitDoesNotDeleteEitherTree() = fixture { f ->
        val originalTreeKey = key(f.root)
        val originalDraftKey = key(f.draft)
        val observer = File(f.app, "observer-retained-original")
        var replacementKey: Any? = null
        f.store.afterCommit = { records ->
            assertFalse(records.any { it.id == f.project.id })
            Files.move(f.recovery.toPath(), observer.toPath())
            check(f.recovery.mkdir())
            File(f.recovery, "new-user-work.txt").writeText("new work at the former recovery name")
            replacementKey = key(f.recovery)
        }

        val failure = runCatching { f.manager.deleteWorkspace(f.project.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains(f.root.path))
        assertTrue(failure.message.orEmpty().contains(f.recovery.path))

        f.assertRemoved()
        assertTrue(runCatching { f.manager.switch("default") }.isFailure)
        assertNotNull(replacementKey)
        assertEquals(originalTreeKey, key(observer))
        assertEquals(originalDraftKey, key(File(observer, "draft.txt")))
        assertEquals("unpublished editor and CLI work", File(observer, "draft.txt").readText())
        assertArrayEquals(f.payload, File(observer, "nested/source.bin").readBytes())
        assertEquals(replacementKey, key(f.recovery))
        assertEquals("new work at the former recovery name", File(f.recovery, "new-user-work.txt").readText())
    }

    @Test fun descriptorWriteAfterCatalogCommitRemainsAccessibleByItsRetainedName() = fixture { f ->
        val draftKey = key(f.draft)
        val late = "external writer finished after the catalog commit".toByteArray()
        FileChannel.open(f.draft.toPath(), READ, WRITE).use { descriptor ->
            f.store.afterCommit = { records ->
                assertFalse(records.any { it.id == f.project.id })
                descriptor.position(0)
                val buffer = ByteBuffer.wrap(late)
                while (buffer.hasRemaining()) descriptor.write(buffer)
                descriptor.truncate(late.size.toLong())
                descriptor.force(true)
            }

            f.manager.deleteWorkspace(f.project.id)

            f.assertRemoved()
            val retained = File(f.recovery, "draft.txt")
            assertEquals(draftKey, key(retained))
            assertArrayEquals(late, retained.readBytes())
            descriptor.position(0)
            val observed = ByteBuffer.allocate(late.size)
            while (observed.hasRemaining()) check(descriptor.read(observed) > 0)
            assertArrayEquals(late, observed.array())
        }
    }

    @Test fun freshManagerDoesNotResurrectTheRemovedProjectOrCollectItsRetainedWork() = fixture { f ->
        val treeKey = key(f.root)
        val draftKey = key(f.draft)
        f.manager.deleteWorkspace(f.project.id)

        val restarted = ProjectManager(f.app)
        restarted.init()
        restarted.collectStartupGarbage()

        f.assertRemoved()
        assertEquals(f.records.filterNot { it.id == f.project.id }, restarted.projects.value)
        assertEquals(f.activeId, restarted.activeId.value)
        assertFalse(restarted.projects.value.any { it.id == f.project.id })
        assertEquals(treeKey, key(f.recovery))
        assertEquals(draftKey, key(File(f.recovery, "draft.txt")))
        assertEquals("unpublished editor and CLI work", File(f.recovery, "draft.txt").readText())
        assertArrayEquals(f.payload, File(f.recovery, "nested/source.bin").readBytes())
    }

    @Test fun lostReturnAfterCommittedRemovalDoesNotDiscardTheRetainedSource() = fixture { f ->
        val treeKey = key(f.root)
        val draftKey = key(f.draft)
        f.store.afterCommit = { records ->
            assertFalse(records.any { it.id == f.project.id })
            throw IOException("fixture committed catalog write lost its return")
        }

        val outcome = f.manager.deleteWorkspace(f.project.id)

        f.assertRemoved()
        assertEquals(f.project.id, outcome?.projectId)
        assertEquals(f.recovery.path, outcome?.recoveryPath)
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals(treeKey, key(f.recovery))
        assertEquals(draftKey, key(File(f.recovery, "draft.txt")))
        assertEquals("unpublished editor and CLI work", File(f.recovery, "draft.txt").readText())
        assertArrayEquals(f.payload, File(f.recovery, "nested/source.bin").readBytes())
    }

    @Test fun retainedNestedLinkAndItsExternalFixtureTargetRemainUnchanged() = fixture { f ->
        val outside = File(f.app, "unrelated-files").apply { check(mkdir()) }
        val externalSource = File(outside, "keep.txt").apply { writeText("external source remains") }
        val externalKey = key(externalSource)
        Files.createSymbolicLink(File(f.root, "dependency").toPath(), outside.toPath())

        f.manager.deleteWorkspace(f.project.id)

        f.assertRemoved()
        val retainedLink = File(f.recovery, "dependency").toPath()
        assertTrue(Files.isSymbolicLink(retainedLink))
        assertEquals(outside.toPath(), Files.readSymbolicLink(retainedLink))
        assertEquals(externalKey, key(externalSource))
        assertEquals("external source remains", externalSource.readText())
        assertEquals("unpublished editor and CLI work", File(f.recovery, "draft.txt").readText())
    }

    @Test fun missingSourceDoesNotReportADetachmentOrInventARecoveryLocation() = fixture { f ->
        val observer = File(f.app, "observer-held-source")
        val originalKey = key(f.root)
        Files.move(f.root.toPath(), observer.toPath())

        val outcome = f.manager.deleteWorkspace(f.project.id)

        f.assertRemoved()
        assertNotNull(outcome)
        assertEquals(f.project.id, outcome!!.projectId)
        assertNull(outcome.recoveryPath)
        assertFalse(f.recovery.exists())
        assertEquals(originalKey, key(observer))
        assertEquals("unpublished editor and CLI work", File(observer, "draft.txt").readText())
        assertArrayEquals(f.payload, File(observer, "nested/source.bin").readBytes())
    }

    @Test fun unknownProjectDoesNotReportRemovalOrChangeTheCatalog() = fixture { f ->
        val catalog = File(f.app, ".droide/projects.json")
        val before = catalog.readBytes()
        val rootKey = key(f.root)
        val draftKey = key(f.draft)

        assertNull(f.manager.deleteWorkspace("p-not-registered"))

        assertEquals(f.records, f.manager.projects.value)
        assertEquals(f.records, f.store.load())
        assertArrayEquals(before, catalog.readBytes())
        assertEquals(rootKey, key(f.root))
        assertEquals(draftKey, key(f.draft))
        assertEquals("unpublished editor and CLI work", f.draft.readText())
        assertFalse(File(f.root.parentFile, ".delete-p-not-registered").exists())
    }
}
