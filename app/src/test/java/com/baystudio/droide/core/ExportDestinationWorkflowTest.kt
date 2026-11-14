package com.baystudio.droide.core

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Same SAF provider surface as the existing collision tests; fault injection keeps stale bytes on w. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExportDestinationWorkflowTest {
    private val home = Files.createTempDirectory("export-destination-workflow-").toFile()
    private val root = File(home, "source").apply { mkdir() }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val tree get() = DocumentsContract.buildTreeDocumentUri("export.test", "parent")
    private lateinit var provider: DocumentsProvider
    private val created = mutableListOf<Triple<String, String, String>>()
    private val modes = mutableListOf<String>()
    private val blobs = File(home, "provider").apply { mkdir() }
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setup() = runBlocking {
        org.robolectric.shadows.ShadowApplication.setProcessName(context.packageName)
        context.applicationInfo.processName = context.packageName
        clearController()
        File(context.filesDir, "exports-v1").deleteRecursively()
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val delegate = ExportSafDestinationTest.Provider(blobs)
        delegate.attachInfo(context, ProviderInfo().apply {
            authority = "export.test"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        provider = delegate
        val bridge = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                provider.query(uri, projection, Bundle(), null)
            override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?) =
                provider.query(uri, projection, queryArgs, cancellationSignal)
            override fun getType(uri: Uri) = provider.getType(uri)
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                modes += mode
                val file = File(blobs, DocumentsContract.getDocumentId(uri))
                if (mode != "r" && !file.exists()) file.writeBytes(ByteArray(1024) { 99 })
                val flags = if (mode == "w") ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
                    else ParcelFileDescriptor.parseMode(mode)
                return ParcelFileDescriptor.open(file, flags)
            }
            override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?) = openFile(uri, mode)
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
                val result = provider.call(method, arg, extras)
                if (method == "android:createDocument") {
                    // DocumentsContract's provider wire key is hidden from the public SDK.
                    val uri = result!!.getParcelable<Uri>("uri")!!
                    created += Triple(DocumentsContract.getDocumentId(uri),
                        extras!!.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!,
                        extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)!!)
                }
                return result
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Not used")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Not used")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Not used")
        }
        bridge.attachInfo(context, ProviderInfo().apply { authority = "export.test" })
        ShadowContentResolver.registerProviderInternal("export.test", bridge)
    }

    @After fun cleanup() = runBlocking {
        withTimeout(20_000) { ExportController.shutdownForExit(context); workerScope.coroutineContext[Job]!!.cancelAndJoin(); clearController() }
        File(context.filesDir, "exports-v1").deleteRecursively()
        PathSecurity.deleteTreeNoFollow(home)
        Unit
    }
    private fun field(name: String) = ExportController.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private suspend fun clearController() {
        (field("worker").get(ExportController) as? Job)?.cancelAndJoin()
        (field("workerCompletion").get(ExportController) as? CompletableDeferred<*>)?.await()
        (field("persistenceScope").get(ExportController) as CoroutineScope).coroutineContext[Job]!!.children.toList().joinAll()
        ExportController::class.java.getDeclaredField("pointerAuthority").apply { isAccessible = true }.let {
            (it.get(ExportController) as? ExportPointerRecovery)?.close()
            it.set(ExportController, null)
        }
        listOf("journal", "pending", "destination", "worker", "workerCompletion").forEach { field(it).set(ExportController, null) }
        (field("mutableState").get(ExportController) as MutableStateFlow<ExportJobState?>).value = null
        field("cancellation").set(ExportController, false); field("interrupted").set(ExportController, false)
    }

    @Test fun selectedFolderNameReachesProviderAndOnlyItsRecursiveContentsAreVerified() = runBlocking {
        val payload = byteArrayOf(0, -1, 1, 13, 10)
        File(root, "dist/app.apk").apply { parentFile!!.mkdirs(); writeBytes(payload) }
        File(root, "dist/.hidden").writeText("included")
        File(root, "dist/empty/nested").mkdirs()
        File(root, "outside.txt").writeText("excluded")
        val owner = ExportSourceAdmission("project", root, object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
        }, {})
        val ticket = owner.prepare(owner.ticket(ExportScope("dist")), false)
        val id = ExportController.start(context, owner, ticket, ExportFormat.TREE, tree, grants, true, outputName = "My release")
        assertTrue(ExportController.launch(workerScope, context, id, ticket.epoch) {})
        withTimeout(15_000) { (field("workerCompletion").get(ExportController) as CompletableDeferred<*>).await() }
        assertEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        assertEquals("My release", created.first().second)
        assertEquals(setOf("My release", "app.apk", ".hidden", "empty", "nested"), created.map { it.second }.toSet())
        val apk = created.single { it.second == "app.apk" }
        assertEquals("application/vnd.android.package-archive", apk.third)
        assertArrayEquals(payload, File(blobs, apk.first).readBytes())
        assertEquals(2, modes.count { it == "wt" })
        assertEquals(2, modes.count { it == "r" })
        assertFalse(modes.contains("w"))
    }

    @Test fun rawFileTruncatesProviderPrefillAndVerifiesOriginalBinaryBytes() {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "parent")
        val target = DocumentsContract.createDocument(context.contentResolver, parent, "image/jpeg", "photo.jpg")!!
        val payload = byteArrayOf(-1, -40, 0, 13, 10, -1, -39)
        File(root, "photo.jpg").writeBytes(payload)
        val permit = object : ExportCapturePermit {
            override val root = this@ExportDestinationWorkflowTest.root
            override val scope = ExportScope("photo.jpg")
            override val stabilityAssumptions = emptyList<String>()
            override fun validate() = Unit
        }
        ExportCaptureStore(File(home, "capture")).capture(permit).use {
            val writer = ExportSafDestination(context.contentResolver, target, grants, ExportFormat.RAW_FILE,
                File(home, "job"), "unused", {}, {})
            ExportEngine().transfer(it, ExportFormat.RAW_FILE, writer)
        }
        assertEquals(listOf("wt", "r"), modes)
        assertArrayEquals(payload, File(blobs, DocumentsContract.getDocumentId(target)).readBytes())
    }

    @Test fun missingReadGrantIsRejectedBeforeQueuingOrCapturingSource() = runBlocking {
        val owner = ExportSourceAdmission("project", root, object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
        }, {})
        val ticket = owner.ticket(ExportScope(""))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ExportController.start(context, owner, ticket, ExportFormat.TREE, tree,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true, outputName = "release") }
        }
        assertNull(ExportController.state.value)
        assertTrue(ExportController.history(context).isEmpty())
        assertTrue(created.isEmpty()); assertTrue(modes.isEmpty())
    }
}
