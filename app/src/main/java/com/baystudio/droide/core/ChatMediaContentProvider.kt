package com.baystudio.droide.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Opaque, read-only grants for registered chat originals, never a grant to an arbitrary workspace path. */
class ChatMediaContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = resolve(uri).first.mediaType

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (ref, _) = resolve(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map<String, Any> { column -> if (column == OpenableColumns.DISPLAY_NAME) ref.name else ref.bytes }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Chat attachments are read-only")
        val (_, file) = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Chat attachments are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Chat attachments are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Chat attachments are read-only")

    private fun resolve(uri: Uri): Pair<ChatMediaRef, File> {
        try {
            val app = requireNotNull(context).applicationContext
            require(uri.scheme == "content" && uri.authority == ChatMediaAccess.authority(app) && uri.query == null && uri.fragment == null)
            val parts = uri.pathSegments
            require(parts.size == 3 && parts[0] == "v1" && ChatMediaAccess.ID.matches(parts[2]))
            val workspace = ChatMediaAccess.workspace(app, ChatMediaAccess.decode(parts[1]))
            // A made-up URI cannot create a store or reveal anything outside an existing media manifest.
            val store = ChatMediaStore(workspace)
            require(store.hasManifest())
            val ref = requireNotNull(store.ref(parts[2]))
            val file = requireNotNull(store.file(ref))
            return ref to file
        } catch (_: Exception) {
            throw FileNotFoundException("Attachment is expired or unavailable; choose the file again.")
        }
    }
}

/** The only provider/cleanup workspace root is the app's internal project store. */
internal object ChatMediaAccess {
    val ID = Regex("m-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{1,1024}")
    fun authority(context: Context): String = "${context.packageName}.chat-media"

    fun relativeWorkspace(context: Context, workDir: File): String {
        val root = File(context.filesDir.canonicalFile, "projects")
        val canonical = workDir.canonicalFile
        require(canonical != root && canonical.toPath().startsWith(root.toPath())) { "Chat media can only be opened from an internal Droide project." }
        val relative = canonical.relativeTo(root).invariantSeparatorsPath
        require(workspace(context, relative) == canonical) { "Workspace path is unavailable" }
        return relative
    }

    fun workspace(context: Context, relative: String, requireExisting: Boolean = true): File {
        require(relative.length in 1..512 && '\\' !in relative && relative.none { it.code < 0x20 || it.code == 0x7f })
        val segments = relative.split('/')
        require(segments.size <= 16 && segments.none { it.isBlank() || it == "." || it == ".." })
        val root = File(context.filesDir.canonicalFile, "projects")
        require(!Files.isSymbolicLink(root.toPath()) && (!root.exists() || root.isDirectory))
        if (requireExisting) require(root.isDirectory)
        var path = root
        segments.forEach { segment ->
            path = File(path, segment)
            require(!Files.isSymbolicLink(path.toPath()) && (!path.exists() || path.isDirectory)) { "Workspace path is unavailable" }
            if (requireExisting) require(path.isDirectory) { "Workspace path is unavailable" }
        }
        require(path.canonicalFile == path && path.toPath().startsWith(root.toPath()))
        return path
    }

    fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun decode(token: String): String {
        require(TOKEN.matches(token))
        val value = Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).toString(Charsets.UTF_8)
        require(encode(value) == token)
        return value
    }

    fun uri(context: Context, workDir: File, ref: ChatMediaRef): Uri {
        require(ID.matches(ref.id))
        val relative = relativeWorkspace(context, workDir)
        val store = ChatMediaStore(workDir)
        require(store.file(ref) != null) { "Attachment is expired or unavailable; choose the file again." }
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath("v1")
            .appendPath(encode(relative)).appendPath(ref.id).build()
    }
}
