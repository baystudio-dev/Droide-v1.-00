package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
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
class ApplicationCatalogReopenPersistenceTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private fun root(): File = Files.createTempDirectory("f163-application-reopen-").toFile()
    private fun metadata(root: File, name: String) = File(root, ".droide/$name")
    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    @Test fun applicationReopenKeepsUnchangedMetadataFiles() = runBlocking {
        val root = root()
        ProjectManager.forApplication(root).init()
        val names = listOf("projects.json", "active_project.txt", "recent_projects.json")
        val before = names.associateWith { key(metadata(root, it)) to metadata(root, it).readBytes() }
        val reopened = ProjectManager.forApplication(root).apply { init() }
        assertEquals("default", reopened.activeId.value)
        for (name in names) {
            assertEquals("Reopen rewrote $name", before.getValue(name).first, key(metadata(root, name)))
            assertArrayEquals(before.getValue(name).second, metadata(root, name).readBytes())
        }
    }

}
