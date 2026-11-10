package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemplateRegistrationTest {
    private class RegisteredStore(private val delegate: FileProjectStore) : ProjectStore by delegate {
        var registered: DroideProject? = null
        var onRegistration: ((DroideProject) -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            delegate.save(all)
            all.singleOrNull { it.id != "default" }?.let { project ->
                registered = project
                onRegistration.also { onRegistration = null }?.invoke(project)
            }
        }
    }
    @Test fun unsafeNestedParentRetainsPartialRegisteredProjectAcrossRestartAndSelection() = runBlocking {
        val app = Files.createTempDirectory("droide-f165-registration-").toFile()
        val external = Files.createTempDirectory("droide-f165-external-").toFile()
        val source = File(external, "main.rs").apply { writeText("external user work") }
        val sourceKey = key(source)
        val store = RegisteredStore(FileProjectStore(app))
        val manager = ProjectManager(app, store).apply { init() }
        var rootKey: Any? = null
        store.onRegistration = { project ->
            val root = File(project.rootPath)
            rootKey = key(root)
            Files.createSymbolicLink(File(root, "src").toPath(), external.toPath())
        }
        val error = try {
            manager.createFromTemplate("Retained Rust", "rust")
            null
        } catch (failure: java.io.IOException) { failure }
        assertNotNull(error)
        val project = checkNotNull(store.registered)
        val root = File(project.rootPath)
        assertEquals(rootKey, key(root))
        assertEquals(ProjectTemplateRegistry.byId("rust")!!.files["Cargo.toml"], File(root, "Cargo.toml").readText())
        val partialKey = key(File(root, "Cargo.toml"))
        assertEquals("default", manager.activeId.value)
        assertEquals(project, manager.projects.value.single { it.id == project.id })
        assertEquals(project, store.load().single { it.id == project.id })
        assertEquals(sourceKey, key(source))
        assertEquals("external user work", source.readText())
        val restarted = ProjectManager(app).apply { init() }
        assertTrue(restarted.collectStartupGarbage().isEmpty())
        restarted.switch(project.id)
        assertEquals(project, restarted.active())
        assertEquals(rootKey, key(root))
        assertEquals(partialKey, key(File(root, "Cargo.toml")))
        assertEquals(sourceKey, key(source))
    }
    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
}
