package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalManagedProjectionParentGuardTest {
    companion object {
        // The substrate is process-owned and correctly rejects a different application root. All methods
        // in this isolated suite therefore use one real app root.
        private val applicationRoot = Files.createTempDirectory("projection-parent-application-").toFile().canonicalFile
    }

    private class Fixture {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = applicationRoot
        }
        val root = context.filesDir.canonicalFile
        val managed = File(root, "managed")
        val external = Files.createTempDirectory(context.cacheDir.toPath(), "projection-sentinel-").toFile()
        val sentinel = File(external, "do-not-change.txt").apply { writeText("outside sentinel") }
        val registry = ManagedPackageRegistry(context)
        var publications = 0
        val authority = LocalManagedPackageAuthority(context, registry) { _, _ -> publications++ }
        init {
            context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit().clear().commit()
            LocalExecutionSubstrate.init(context)
        }
        fun record() {
            val packageRoot = File(root, "packages/cli.parent-fixture/1/arm64-v8a").apply { mkdirs() }
            val source = File(packageRoot, "payload/host-bin/parent-tool").apply {
                parentFile!!.mkdirs(); writeText("#!/system/bin/sh\nexit 0\n"); setExecutable(true)
            }
            registry.put(ManagedPackageRecord("cli.parent-fixture", "1", ExecutionScope.LOCAL_LINUX_ARM64.name,
                packageRoot.path, 1, commands = mapOf("parent-tool" to source.path), abi = "arm64-v8a",
                metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                    LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW)))
        }
        fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().associate { file ->
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            file.relativeTo(directory).path to "${attrs.fileKey()}|${attrs.size()}|${attrs.lastModifiedTime()}|${if (file.isFile) file.readText() else "directory"}"
        }
        fun close() {
            context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE).edit().clear().commit()
            PathSecurity.deleteTreeNoFollow(managed)
            PathSecurity.deleteTreeNoFollow(external)
            PathSecurity.deleteTreeNoFollow(File(root, "packages/cli.parent-fixture"))
        }
    }

    @Test fun healthyProjectionRepeatedRefreshDoesNotPublishOrRewrite() = runBlocking {
        val f = Fixture()
        try {
            f.record(); f.authority.reconcile()
            val before = f.snapshot(f.managed)
            f.publications = 0
            repeat(2) { f.authority.reconcile() }
            assertEquals(0, f.publications)
            assertEquals(before, f.snapshot(f.managed))
        } finally { f.close() }
    }

    @Test fun matchingProjectionRejectsManagedParentSymlinkWithoutTouchingOutside() = runBlocking {
        val f = Fixture()
        try {
            f.record(); f.authority.reconcile()
            val moved = File(f.external, "managed")
            Files.move(f.managed.toPath(), moved.toPath())
            Files.createSymbolicLink(f.managed.toPath(), moved.toPath())
            val before = f.snapshot(f.external)
            f.publications = 0
            val failure = runCatching { f.authority.reconcile() }.exceptionOrNull()
            assertNotNull("A healthy-byte fast return must still reject a symbolic managed parent", failure)
            assertEquals(0, f.publications)
            assertEquals(before, f.snapshot(f.external))
        } finally { f.close() }
    }

    @Test fun emptyProjectionRejectsManagedParentSymlinkWithoutTouchingOutside() = runBlocking {
        val f = Fixture()
        try {
            f.authority.reconcile()
            val moved = File(f.external, "managed")
            Files.move(f.managed.toPath(), moved.toPath())
            Files.createSymbolicLink(f.managed.toPath(), moved.toPath())
            val before = f.snapshot(f.external)
            val failure = runCatching { f.authority.reconcile() }.exceptionOrNull()
            assertNotNull("Empty matching directories do not waive parent validation", failure)
            assertEquals(0, f.publications)
            assertEquals(before, f.snapshot(f.external))
        } finally { f.close() }
    }

    @Test fun regularFileManagedParentIsPreservedWithoutProjectionWrites() = runBlocking {
        val f = Fixture()
        try {
            f.managed.writeText("preserve non-directory")
            val before = f.snapshot(f.external)
            assertNotNull(runCatching { f.authority.reconcile() }.exceptionOrNull())
            assertEquals("preserve non-directory", f.managed.readText())
            assertEquals(0, f.publications)
            assertEquals(before, f.snapshot(f.external))
        } finally { f.close() }
    }

    @Test fun fallbackRejectsUnsafeStagingBeforeCreatingDestination() = runBlocking {
        val f = Fixture()
        try {
            f.managed.mkdir()
            Files.createSymbolicLink(File(f.managed, ".staging").toPath(), f.external.toPath())
            val before = f.snapshot(f.external)
            assertNotNull(runCatching { f.authority.reconcile() }.exceptionOrNull())
            assertFalse("Fallback must validate all projection directories before mkdir", File(f.managed, "bin").exists())
            assertEquals(0, f.publications)
            assertEquals(before, f.snapshot(f.external))
        } finally { f.close() }
    }

    @Test fun fallbackRejectsNonDirectoryStagingBeforeCreatingDestination() = runBlocking {
        val f = Fixture()
        try {
            f.managed.mkdir()
            File(f.managed, ".staging").writeText("keep staging sentinel")
            assertNotNull(runCatching { f.authority.reconcile() }.exceptionOrNull())
            assertFalse("A non-directory staging entry must fail before creating bin", File(f.managed, "bin").exists())
            assertEquals("keep staging sentinel", File(f.managed, ".staging").readText())
            assertEquals(0, f.publications)
            assertEquals("outside sentinel", f.sentinel.readText())
        } finally { f.close() }
    }
}
