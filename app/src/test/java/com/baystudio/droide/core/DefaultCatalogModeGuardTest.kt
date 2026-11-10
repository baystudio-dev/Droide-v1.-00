package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultCatalogModeGuardTest {
    @get:Rule val temp = TemporaryFolder()
    private val evidence = listOf("catalog-updates", "catalog-update-membership.json",
        ".catalog-update-membership.json.pending", "startup-admission.json", ".startup-admission.json.pending")

    @Test fun absentStorageIsReadOnlyAndOrdinaryRegistrationEvidenceIsAllowed() {
        val app = temp.newFolder()
        val guard = DefaultCatalogModeGuard(app)
        guard.requireDefaultMode()
        assertTrue(app.list()!!.isEmpty())
        val storage = app.resolve("catalog-startup").apply { mkdir() }
        listOf("initial-project-registration", "catalog-membership.json", ".catalog-membership.json.pending", "notes.txt")
            .forEach { storage.resolve(it).writeText("keep") }
        repeat(3) { guard.requireDefaultMode() }
        assertEquals(4, storage.list()!!.size)
        storage.listFiles()!!.forEach { assertEquals("keep", it.readText()) }
    }

    @Test fun everyReservedUpdateNameRejectsFileDirectoryAndDanglingLinkWithoutModification() {
        for (name in evidence) for (kind in 0..2) {
            val app = temp.newFolder()
            val storage = app.resolve("catalog-startup").apply { mkdir() }
            val target = storage.resolve(name)
            when (kind) {
                0 -> target.writeText("retained evidence")
                1 -> target.mkdir()
                2 -> Files.createSymbolicLink(target.toPath(), app.resolve("missing").toPath())
            }
            val identity = Files.getAttribute(target.toPath(), "unix:ino", NOFOLLOW_LINKS)
            assertTrue(runCatching { DefaultCatalogModeGuard(app).requireDefaultMode() }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
            assertEquals(identity, Files.getAttribute(target.toPath(), "unix:ino", NOFOLLOW_LINKS))
            if (kind == 0) assertEquals("retained evidence", target.readText())
        }
    }

    @Test fun unsafeStorageIsNotTraversed() {
        for (link in listOf(false, true)) {
            val app = temp.newFolder()
            val target = temp.newFolder().apply { resolve("keep").writeText("external") }
            val storage = app.resolve("catalog-startup")
            if (link) Files.createSymbolicLink(storage.toPath(), target.toPath()) else storage.writeText("not directory")
            assertTrue(runCatching { DefaultCatalogModeGuard(app).requireDefaultMode() }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
            assertEquals("external", target.resolve("keep").readText())
        }
    }

    @Test fun observedEvidenceBlocksInstanceEvenIfMovedButDoesNotPersistNewEvidence() {
        val app = temp.newFolder()
        val storage = app.resolve("catalog-startup").apply { mkdir() }
        val source = storage.resolve("startup-admission.json").apply { writeText("keep") }
        val guard = DefaultCatalogModeGuard(app)
        val first = runCatching { guard.requireDefaultMode() }.exceptionOrNull()
        Files.move(source.toPath(), app.resolve("preserved-admission").toPath())
        assertSame(first, runCatching { guard.requireDefaultMode() }.exceptionOrNull())
        DefaultCatalogModeGuard(app).requireDefaultMode()
        assertEquals("keep", app.resolve("preserved-admission").readText())
        assertTrue(storage.list()!!.isEmpty())
    }

    @Test fun replacingPreviouslyObservedStorageDoesNotResetModeAdmission() {
        val app = temp.newFolder()
        val storage = app.resolve("catalog-startup").apply { mkdir() }
        val guard = DefaultCatalogModeGuard(app)
        guard.requireDefaultMode()
        Files.move(storage.toPath(), app.resolve("preserved-storage").toPath())
        storage.mkdir()
        assertTrue(runCatching { guard.requireDefaultMode() }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        assertTrue(app.resolve("preserved-storage").isDirectory)
    }
}
