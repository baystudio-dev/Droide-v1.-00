package com.baystudio.droide.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Real filesystem and DocumentsProvider bytes; no Android-device/native race certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafMirrorPreservationRegressionTest {
    private val temporaryRoots = mutableListOf<File>()
    private val original = byteArrayOf(0, 1, 2, 3, 127, -1, 10)

    @After fun cleanUp() {
        try {
            assertEquals("Directory durability descriptors must close", 0, SafPreservationDirectoryOs.openCount())
        } finally {
            SafPreservationDirectoryOs.reset()
            temporaryRoots.forEach { PathSecurity.deleteTreeNoFollow(it) }
        }
    }

    @Test fun newNestedNodeModulesSurvivesFirstAndSecondFullReconcile() = runBlocking {
        val fixture = fixture()
        val nestedBytes = byteArrayOf(-1, 0, 42, 10, 99)
        fixture.local.resolve("node_modules/pkg/deep/index.js").apply {
            parentFile!!.mkdirs()
            writeBytes(nestedBytes)
        }
        fixture.local.resolve("node_modules/pkg/empty").mkdirs()
        fixture.local.resolve(".git/objects/local-sentinel").apply { parentFile!!.mkdirs(); writeBytes(original) }
        fixture.local.resolve(".droide/local-sentinel").apply { parentFile!!.mkdirs(); writeBytes(original) }
        fixture.external.resolve(".git/remote-only").apply { parentFile!!.mkdirs(); writeText("remote git") }
        fixture.external.resolve(".droide/remote-only").apply { parentFile!!.mkdirs(); writeText("remote metadata") }

        repeat(2) {
            val result = fixture.mirror().refreshFromTree()
            assertTrue(result.startsWith("SAF synchronized:"))
            assertArrayEquals(original, fixture.local.resolve("sentinel.bin").readBytes())
            assertArrayEquals(original, fixture.external.resolve("sentinel.bin").readBytes())
            assertArrayEquals(nestedBytes, fixture.local.resolve("node_modules/pkg/deep/index.js").readBytes())
            assertArrayEquals(nestedBytes, fixture.external.resolve("node_modules/pkg/deep/index.js").readBytes())
            assertTrue(fixture.local.resolve("node_modules/pkg/empty").isDirectory)
            assertTrue(fixture.external.resolve("node_modules/pkg/empty").isDirectory)
            assertArrayEquals(original, fixture.local.resolve(".git/objects/local-sentinel").readBytes())
            assertArrayEquals(original, fixture.local.resolve(".droide/local-sentinel").readBytes())
            assertFalse(fixture.local.resolve(".git/remote-only").exists())
            assertFalse(fixture.local.resolve(".droide/remote-only").exists())
            assertTrue(fixture.local.resolve(".droide/saf_manifest.json").readText().contains("node_modules/pkg/deep/index.js"))
            assertFalse(fixture.marker.exists())
            assertTrue(fixture.artifacts("stage").isEmpty())
            assertTrue(fixture.artifacts("backup").isEmpty())
        }
    }

    @Test fun danglingSymlinkStopsRefreshBeforeAnyProviderMutation() = runBlocking {
        val fixture = fixture()
        val link = fixture.local.resolve("dangling")
        val target = java.nio.file.Paths.get("missing-target")
        Files.createSymbolicLink(link.toPath(), target)

        val failure = runCatching { fixture.mirror().refreshFromTree() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(target, Files.readSymbolicLink(link.toPath()))
        assertEquals(0, fixture.provider.mutations)
        assertArrayEquals(original, fixture.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(original, fixture.external.resolve("sentinel.bin").readBytes())
        assertFalse(fixture.marker.exists())
    }

    @Test fun directorySymlinkIsNeitherTraversedNorLost() = runBlocking {
        val fixture = fixture()
        val outside = fixture.parent.resolve("outside").apply { mkdirs() }
        outside.resolve("outside-sentinel").writeBytes(original)
        val link = fixture.local.resolve("linked-directory")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val failure = runCatching { fixture.mirror().refreshFromTree() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(link.toPath()))
        assertArrayEquals(original, outside.resolve("outside-sentinel").readBytes())
        assertArrayEquals(original, fixture.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(original, fixture.external.resolve("sentinel.bin").readBytes())
        assertEquals(0, fixture.provider.mutations)
        assertFalse(fixture.external.resolve("linked-directory").exists())
    }

    @Test fun partialActivationRetainsOriginalsAndBlocksOnlyOverlappingNewInstanceRetries() = runBlocking {
        val fixture = fixture(faultingRoot = true)
        fixture.local.resolve("a.bin").writeBytes(original)
        fixture.external.resolve("a.bin").writeBytes(original)
        fixture.local.resolve("b.bin").writeBytes(original)
        fixture.external.resolve("b.bin").writeBytes(original)
        fixture.mirror().refreshFromTree()
        val manifestBefore = fixture.local.resolve(".droide/saf_manifest.json").readText()
        val externalNew = byteArrayOf(40, 41, 42)
        fixture.external.resolve("a.bin").writeBytes(externalNew)
        (fixture.local as FaultingLocalRoot).failOnName = "b.bin"

        val failure = runCatching { fixture.mirror().refreshFromTree() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("retained"))
        val stage = fixture.artifacts("stage").single()
        val backup = fixture.artifacts("backup").single()
        assertArrayEquals(original, backup.resolve("a.bin").readBytes())
        assertArrayEquals(externalNew, stage.resolve("a.bin").readBytes())
        assertArrayEquals(original, fixture.local.resolve("b.bin").readBytes())
        assertEquals(manifestBefore, fixture.local.resolve(".droide/saf_manifest.json").readText())
        val markerBefore = fixture.marker.readBytes()
        val newerLocal = byteArrayOf(90, 91, 92)
        fixture.local.resolve("a.bin").writeBytes(newerLocal)
        val retry = fixture.mirror(File(fixture.local.path))
        val mutationsBefore = fixture.provider.mutations

        assertTrue(runCatching { retry.refreshFromTree() }.isFailure)
        assertTrue(runCatching { retry.apply(FileRepository.Mutation.Write("a.bin")) }.isFailure)
        assertTrue(runCatching { retry.apply(FileRepository.Mutation.Move("new.bin", "a.bin")) }.isFailure)
        Files.createSymbolicLink(fixture.local.resolve("alias.bin").toPath(), java.nio.file.Paths.get("a.bin"))
        assertTrue(runCatching { retry.apply(FileRepository.Mutation.Write("alias.bin")) }.isFailure)
        assertEquals(mutationsBefore, fixture.provider.mutations)
        assertArrayEquals(newerLocal, fixture.local.resolve("a.bin").readBytes())
        assertArrayEquals(original, backup.resolve("a.bin").readBytes())
        assertArrayEquals(externalNew, stage.resolve("a.bin").readBytes())
        assertArrayEquals(externalNew, fixture.external.resolve("a.bin").readBytes())

        fixture.local.resolve("new.bin").writeBytes(original)
        retry.apply(FileRepository.Mutation.Write("new.bin"))
        assertArrayEquals(original, fixture.external.resolve("new.bin").readBytes())
        assertArrayEquals(markerBefore, fixture.marker.readBytes())
        assertArrayEquals(original, backup.resolve("a.bin").readBytes())
        assertArrayEquals(externalNew, stage.resolve("a.bin").readBytes())
        val manifestAfter = fixture.local.resolve(".droide/saf_manifest.json").readText()
        val beforeEntries = kotlinx.serialization.json.Json.parseToJsonElement(manifestBefore)
            .let { it as kotlinx.serialization.json.JsonObject }["entries"] as kotlinx.serialization.json.JsonObject
        val afterEntries = kotlinx.serialization.json.Json.parseToJsonElement(manifestAfter)
            .let { it as kotlinx.serialization.json.JsonObject }["entries"] as kotlinx.serialization.json.JsonObject
        beforeEntries.forEach { (path, value) -> assertEquals(value, afterEntries[path]) }
        assertTrue(afterEntries.containsKey("new.bin"))
        assertTrue(runCatching { retry.refreshFromTree() }.isFailure)
    }

    @Test fun markerClearFailureReportsFailureAndRetainsOriginalBackup() = runBlocking {
        val fixture = fixture()
        val mirror = fixture.mirror()
        // Filesystem fault injection only; production has no test callback or recovery bypass.
        val field = SafMirror::class.java.getDeclaredField("activationMarker").apply { isAccessible = true }
        field.set(mirror, object : File(fixture.marker.path) {
            override fun delete(): Boolean = false
        })

        val failure = runCatching { mirror.refreshFromTree() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("could not be cleared"))
        assertTrue(fixture.marker.isFile)
        assertEquals(1, fixture.artifacts("stage").size)
        assertArrayEquals(original, fixture.artifacts("backup").single().resolve("sentinel.bin").readBytes())
        assertArrayEquals(original, fixture.local.resolve("sentinel.bin").readBytes())
        assertTrue(runCatching { fixture.mirror().refreshFromTree() }.isFailure)
    }

    @Test fun malformedOrUnknownActivationMarkerFailsClosedWithoutDeletingEvidence() = runBlocking {
        val fixture = fixture()
        fixture.marker.parentFile!!.mkdirs()
        val samples = listOf("{", """{"version":99,"roots":[],"stage":"retained-stage","backup":"retained-backup"}""")
        samples.forEach { raw ->
            fixture.marker.writeText(raw)
            val mirror = fixture.mirror()
            assertTrue(runCatching { mirror.refreshFromTree() }.isFailure)
            assertTrue(runCatching { mirror.apply(FileRepository.Mutation.CreateDirectory("unrelated")) }.isFailure)
            assertEquals(raw, fixture.marker.readText())
            assertArrayEquals(original, fixture.local.resolve("sentinel.bin").readBytes())
            assertArrayEquals(original, fixture.external.resolve("sentinel.bin").readBytes())
            assertEquals(0, fixture.provider.mutations)
        }
    }

    @Test fun activationMarkerSymlinkAndMetadataDirectoryAliasFailClosed() = runBlocking {
        val markerFixture = fixture()
        markerFixture.marker.parentFile!!.mkdirs()
        val actual = markerFixture.local.resolve(".droide/retained-marker.json")
        actual.writeText("""{"version":1,"roots":["sentinel.bin"],"stage":"retained-stage","backup":"retained-backup"}""")
        val originalRecord = actual.readBytes()
        Files.createSymbolicLink(markerFixture.marker.toPath(), java.nio.file.Paths.get("retained-marker.json"))
        assertTrue(runCatching { markerFixture.mirror().refreshFromTree() }.isFailure)
        assertTrue(Files.isSymbolicLink(markerFixture.marker.toPath()))
        assertArrayEquals(originalRecord, actual.readBytes())
        assertEquals(0, markerFixture.provider.mutations)

        val directoryFixture = fixture()
        val metadata = directoryFixture.local.resolve("metadata").apply { mkdirs() }
        metadata.resolve("sentinel").writeBytes(original)
        Files.createSymbolicLink(directoryFixture.local.resolve(".droide").toPath(), java.nio.file.Paths.get("metadata"))
        assertTrue(runCatching { directoryFixture.mirror().refreshFromTree() }.isFailure)
        assertTrue(Files.isSymbolicLink(directoryFixture.local.resolve(".droide").toPath()))
        assertArrayEquals(original, metadata.resolve("sentinel").readBytes())
        assertArrayEquals(original, directoryFixture.local.resolve("sentinel.bin").readBytes())
        assertEquals(0, directoryFixture.provider.mutations)
    }

    private fun fixture(faultingRoot: Boolean = false): Fixture {
        val parent = Files.createTempDirectory("droide-saf-preservation-").toFile().also { temporaryRoots += it }
        SafPreservationDirectoryOs.allow(parent)
        // This application filesDir belongs to Robolectric's isolated test sandbox.
        val journalMetadata = RuntimeEnvironment.getApplication().filesDir.resolve(".droide").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(journalMetadata)
        val ownedJournal = RuntimeEnvironment.getApplication().filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(ownedJournal)
        val local = if (faultingRoot) FaultingLocalRoot(File(parent, "local").path) else File(parent, "local")
        local.mkdirs()
        val external = File(parent, "external").apply { mkdirs() }
        local.resolve("sentinel.bin").writeBytes(original)
        external.resolve("sentinel.bin").writeBytes(original)
        val authority = "droide.saf.test.${UUID.randomUUID()}"
        val provider = FixtureProvider(external)
        val application = RuntimeEnvironment.getApplication()
        val providerInfo = ProviderInfo().apply {
            this.authority = authority
            name = "$authority.FixtureProvider"
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            enabled = true
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider.attachInfo(application, providerInfo)
        // isDocumentUri also requires PackageManager discovery, separately from resolver dispatch.
        Shadows.shadowOf(application.packageManager).apply {
            addOrUpdateProvider(providerInfo)
            addIntentFilterForProvider(ComponentName(providerInfo.packageName, providerInfo.name),
                IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        // Robolectric 4.14.1 dispatches legacy queries directly; SDK35 DocumentsProvider
        // only implements the Bundle transport. Match Android's transport adaptation.
        val bridge = LegacyResolverBridge(provider).apply {
            attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { this.authority = authority })
        }
        ShadowContentResolver.registerProviderInternal(authority, bridge)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/sentinel.bin")
        check(DocumentsContract.isDocumentUri(application, childUri)) { "SAF fixture provider is not discoverable" }
        check(androidx.documentfile.provider.DocumentFile.fromTreeUri(application, childUri)?.uri == childUri) {
            "SAF fixture tree wrapper did not retain its child identity"
        }
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root")
        requireNotNull(resolver.query(rootUri, null, null, null, null)).use { rows ->
            check(rows.moveToFirst()) { "SAF fixture root query returned no rows" }
            check(rows.getString(rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
                DocumentsContract.Document.MIME_TYPE_DIR) { "SAF fixture root is not readable as a directory" }
        }
        return Fixture(parent, local, external, provider, treeUri)
    }

    private data class Fixture(val parent: File, val local: File, val external: File, val provider: FixtureProvider, val uri: Uri) {
        val marker get() = local.resolve(".droide/saf_activation_pending.json")
        fun mirror(root: File = local) = SafMirror(RuntimeEnvironment.getApplication(), uri.toString(), root)
        fun artifacts(kind: String): List<File> = parent.listFiles().orEmpty().filter { it.name.startsWith(".local.saf-$kind-") }
    }

    /** Fail the second old-file move deterministically, after the first original has been backed up. */
    private class FaultingLocalRoot(path: String) : File(path) {
        var failOnName: String? = null
        override fun listFiles(): Array<File>? = super.listFiles()?.sortedBy { it.name }?.map { entry ->
            if (entry.name != failOnName) entry else object : File(entry.path) {
                override fun toPath(): Path = throw IOException("Injected local activation move failure")
            }
        }?.toTypedArray()
    }

    /** Test transport only; delegates all real document bytes and mutation results unchanged. */
    private class LegacyResolverBridge(private val provider: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            query(uri, projection, selection, selectionArgs, sortOrder, null)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? =
            provider.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }, cancellationSignal)
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? =
            provider.query(uri, projection, queryArgs, cancellationSignal)
        override fun getType(uri: Uri): String? = provider.getType(uri)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = provider.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = provider.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = provider.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Fixture uses DocumentsContract creation")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture uses DocumentsContract deletion")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture uses DocumentsContract writes")
    }

    private class FixtureProvider(private val root: File) : DocumentsProvider() {
        var mutations = 0
        override fun onCreate(): Boolean = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(
            projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        )
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).apply {
            row(this, documentId, file(documentId))
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                row(this, "$parentDocumentId/${child.name}", child)
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if ('w' in mode) mutations++
            return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val child = file(parentDocumentId).resolve(PathSecurity.safeLeafName(displayName))
            check(!child.exists())
            check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile())
            mutations++
            return "$parentDocumentId/$displayName"
        }
        override fun deleteDocument(documentId: String) {
            check(PathSecurity.deleteTreeNoFollow(file(documentId)))
            mutations++
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File {
            require(id == "root" || id.startsWith("root/"))
            return if (id == "root") root else PathSecurity.resolveWithin(root, id.removePrefix("root/"))
        }
        private fun cursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        ))
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any>(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to if (file.isFile) file.length() else 0L,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
                DocumentsContract.Document.COLUMN_FLAGS to (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE),
            )
            cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
        }
    }
}

/**
 * Robolectric 4.14.1 ShadowLinux.open uses RandomAccessFile, which cannot open directories.
 * Only registered test-owned directories use real host directory channels here. A successful
 * fsync means FileChannel.force(true) returned; this adapter does not model crash survival.
 */
@Implements(Os::class)
class SafPreservationDirectoryOs {
    companion object {
        private data class DirectoryHandle(val channel: FileChannel, val path: Path, val key: Any, val stat: StructStat)
        private val roots = linkedMapOf<Path, Any>()
        private val directories = java.util.IdentityHashMap<FileDescriptor, DirectoryHandle>()

        fun allow(directory: File) {
            val path = directory.toPath().toAbsolutePath().normalize()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isDirectory && !attributes.isSymbolicLink)
            roots[path] = requireNotNull(attributes.fileKey())
        }

        fun openCount() = directories.values.count { it.channel.isOpen }

        @JvmStatic @Resetter fun reset() {
            try {
                directories.values.forEach { it.channel.close() }
            } finally {
                directories.clear()
                roots.clear()
            }
        }

        @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            val candidate = File(path).toPath().toAbsolutePath().normalize()
            val owner = roots.entries.firstOrNull { candidate.startsWith(it.key) }
            val guardedRead = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
            if ((flags == OsConstants.O_RDONLY || flags == guardedRead) && owner != null &&
                Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    val root = Files.readAttributes(owner.key, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    check(root.isDirectory && !root.isSymbolicLink && root.fileKey() == owner.value)
                    var current = owner.key
                    owner.key.relativize(candidate).forEach { segment ->
                        current = current.resolve(segment)
                        val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        check(attributes.isDirectory && !attributes.isSymbolicLink)
                    }
                    val before = Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val channel = FileChannel.open(candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                    try {
                        val unix = Files.readAttributes(candidate,
                            "unix:dev,ino,mode,nlink,uid,gid,rdev,ctime", LinkOption.NOFOLLOW_LINKS)
                        fun number(name: String) = (unix.getValue(name) as Number).toLong()
                        val after = Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        check(after.isDirectory && !after.isSymbolicLink && after.fileKey() == before.fileKey())
                        val mode = number("mode").toInt()
                        check(OsConstants.S_ISDIR(mode))
                        val stat = StructStat(number("dev"), number("ino"), mode, number("nlink"),
                            number("uid").toInt(), number("gid").toInt(), number("rdev"), after.size(),
                            after.lastAccessTime().toMillis() / 1000, after.lastModifiedTime().toMillis() / 1000,
                            (unix.getValue("ctime") as FileTime).toMillis() / 1000, 0, 0)
                        return FileDescriptor().also {
                            directories[it] = DirectoryHandle(channel, candidate, requireNotNull(after.fileKey()), stat)
                        }
                    } catch (failure: Throwable) {
                        channel.close()
                        throw failure
                    }
                } catch (failure: IOException) {
                    throw ErrnoException("open", OsConstants.EIO, failure)
                }
            }
            return Shadow.directlyOn(Os::class.java, "open",
                ClassParameter.from(String::class.java, path),
                ClassParameter.from(Int::class.javaPrimitiveType!!, flags),
                ClassParameter.from(Int::class.javaPrimitiveType!!, mode))
        }

        @JvmStatic @Implementation fun fstat(descriptor: FileDescriptor): StructStat {
            val directory = directories[descriptor] ?: return Shadow.directlyOn(Os::class.java, "fstat",
                ClassParameter.from(FileDescriptor::class.java, descriptor))
            if (!directory.channel.isOpen) throw ErrnoException("fstat", OsConstants.EBADF)
            try {
                val current = Files.readAttributes(directory.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(current.isDirectory && !current.isSymbolicLink && current.fileKey() == directory.key) {
                    "Fixture directory descriptor no longer matches its opened identity"
                }
                return directory.stat
            } catch (failure: IOException) {
                throw ErrnoException("fstat", OsConstants.EIO, failure)
            }
        }

        @JvmStatic @Implementation fun fsync(descriptor: FileDescriptor) {
            val directory = directories[descriptor]
            if (directory == null) {
                Shadow.directlyOn<Any?, Os>(Os::class.java, "fsync", ClassParameter.from(FileDescriptor::class.java, descriptor))
            } else {
                try {
                    directory.channel.force(true)
                } catch (failure: IOException) {
                    throw ErrnoException("fsync", OsConstants.EIO, failure)
                }
            }
        }

        @JvmStatic @Implementation fun close(descriptor: FileDescriptor) {
            // Keep closed tokens until reset so fstat cannot delegate a stale owned handle.
            val directory = directories[descriptor]
            if (directory == null) {
                Shadow.directlyOn<Any?, Os>(Os::class.java, "close", ClassParameter.from(FileDescriptor::class.java, descriptor))
            } else {
                if (!directory.channel.isOpen) throw ErrnoException("close", OsConstants.EBADF)
                try {
                    directory.channel.close()
                } catch (failure: IOException) {
                    throw ErrnoException("close", OsConstants.EIO, failure)
                }
            }
        }
    }
}
