package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import java.io.File
import java.nio.file.Files
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
class LocalManagedProjectionRefreshTest {
    @Test fun realReconcileKeepsHealthyShimDirectoriesStableAndRepairsActualChanges() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = context.filesDir.canonicalFile
        val prefs = context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        LocalExecutionSubstrate.init(context)
        val registry = ManagedPackageRegistry(context)
        var observe: ((File, String) -> Unit)? = null
        val authority = LocalManagedPackageAuthority(context, registry) { directory, name -> observe?.invoke(directory, name) }
        val packageRoot = File(root, "packages/cli.audit-fixture/1/arm64-v8a").apply { check(mkdirs()) }
        val source = File(packageRoot, "payload/host-bin/audit-tool").apply {
            parentFile!!.mkdirs(); writeText("#!/system/bin/sh\nexit 0\n"); setExecutable(true)
        }
        val record = ManagedPackageRecord("cli.audit-fixture", "1", ExecutionScope.LOCAL_LINUX_ARM64.name,
            packageRoot.path, 1, commands = mapOf("audit-tool" to source.path), abi = "arm64-v8a",
            metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW))
        registry.put(record)
        val stableRoot = File(root, "packages/cli.audit-stable/1/arm64-v8a").apply { mkdirs() }
        val stableSource = File(stableRoot, "payload/host-bin/stable-tool").apply {
            parentFile!!.mkdirs(); writeText("#!/system/bin/sh\nexit 0\n"); setExecutable(true)
        }
        registry.put(record.copy(familyId = "cli.audit-stable", installRoot = stableRoot.path,
            commands = mapOf("stable-tool" to stableSource.path)))
        fun identity(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let {
            Triple(it.fileKey(), it.lastModifiedTime(), it.size())
        }
        val host = File(root, "managed/bin")
        val guest = File(root, "managed/ubuntu-bin")
        try {
            authority.reconcile()
            val before = listOf(host, guest, File(host, "audit-tool"), File(guest, "audit-tool")).map(::identity)
            repeat(5) { authority.reconcile() }
            assertEquals(before, listOf(host, guest, File(host, "audit-tool"), File(guest, "audit-tool")).map(::identity))
            val correct = File(guest, "audit-tool").readText()
            File(guest, "audit-tool").writeText("broken-shim")
            authority.reconcile()
            assertEquals(correct, File(guest, "audit-tool").readText())
            assertEquals("#!/system/bin/sh\nexit 0\n", source.readText())
            val staged = File(root, "managed/.ubuntu-staging").apply { mkdirs() }
            File(staged, "interrupted").writeText("staged generation")
            authority.reconcile()
            assertEquals("staged generation", File(staged, "interrupted").readText())
            assertEquals(correct, File(guest, "audit-tool").readText())

            // Observe the real authority at every changed-name publication, not just before
            // and after reconcile. Directory and unrelated command identities remain stable.
            val directoryKeys = listOf(host, guest).associateWith { identity(it).first }
            val stableFiles = listOf(File(host, "stable-tool"), File(guest, "stable-tool"))
            val stableIdentities = stableFiles.associateWith(::identity)
            val stableContents = stableFiles.associateWith { it.readText() }
            listOf(host, guest).forEach { File(it, "user-notes.txt").writeText("keep unknown file") }
            val previous = File(root, "managed/.ubuntu-previous").apply { mkdirs() }
            File(previous, "user-journal.txt").writeText("keep unknown journal")
            var publications = 0
            var expectAuditTool = true
            observe = { _, _ ->
                publications++
                directoryKeys.forEach { (directory, key) ->
                    assertTrue(directory.isDirectory)
                    assertEquals(key, identity(directory).first)
                    assertEquals("keep unknown file", File(directory, "user-notes.txt").readText())
                    if (expectAuditTool) assertTrue(File(directory, "audit-tool").isFile)
                }
                stableFiles.forEach { file ->
                    assertEquals(stableIdentities[file], identity(file))
                    assertEquals(stableContents[file], file.readText())
                }
            }
            val nextRoot = File(root, "packages/cli.audit-fixture/2/arm64-v8a").apply { mkdirs() }
            val nextSource = File(nextRoot, "payload/host-bin/audit-tool").apply {
                parentFile!!.mkdirs(); writeText("#!/system/bin/sh\nexit 0\n"); setExecutable(true)
            }
            registry.put(record.copy(version = "2", installRoot = nextRoot.path, commands = mapOf("audit-tool" to nextSource.path)))
            authority.reconcile()
            assertEquals(2, publications)
            assertTrue(File(host, "audit-tool").readText().contains(nextRoot.path))
            assertTrue(File(guest, "audit-tool").readText().contains("/cli.audit-fixture/2/"))
            assertEquals("keep unknown journal", File(previous, "user-journal.txt").readText())
            assertEquals("staged generation", File(staged, "interrupted").readText())
            expectAuditTool = false
            registry.remove(record.familyId, "2")
            authority.reconcile()
            assertEquals(4, publications)
            assertFalse(File(host, "audit-tool").exists())
            assertFalse(File(guest, "audit-tool").exists())
            assertEquals("keep unknown file", File(guest, "user-notes.txt").readText())
        } finally {
            prefs.edit().clear().commit()
            PathSecurity.deleteTreeNoFollow(File(root, "managed"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/cli.audit-fixture"))
            PathSecurity.deleteTreeNoFollow(File(root, "packages/cli.audit-stable"))
        }
    }
}
