package com.baystudio.droide.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class ExportTimestampSafTest {
    @Test fun fractionalTimestampCaptureTransfersThroughSafAndVerifiesSavedBytes() {
        val home = Files.createTempDirectory("timestamp-saf-").toFile()
        try {
            val root = File(home, "project").apply { mkdir() }
            val file = File(root,"test_device.sh").apply { writeText("#!/bin/sh\necho stable\n") }
            Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.ofEpochSecond(1_800_000_000,123456789)))
            val stamp = ExportFileStamp.read(file.toPath())
            val permit = object : VerifiedFileExportCapturePermit {
                override val root = root
                override val scope = ExportScope(file.name)
                override val selectedFileIdentity = stamp.identity
                override val verifySingleFile = true
                override val stabilityAssumptions = emptyList<String>()
                override fun validate() = Unit
            }
            val savedBytes = file.readBytes()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val output = File(home,"exported.sh")
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)).apply {
                        addRow(arrayOf<Any>("exported.sh", DocumentsContract.Document.FLAG_SUPPORTS_WRITE))
                    }
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.parseMode(mode))
                override fun getType(uri: Uri) = "application/octet-stream"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("not used")
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("not used")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("not used")
            }
            provider.attachInfo(context, ProviderInfo().apply { authority="export.precision"; exported=true; grantUriPermissions=true })
            ShadowContentResolver.registerProviderInternal("export.precision", provider)
            val destination = ExportSafDestination(context.contentResolver,
                DocumentsContract.buildDocumentUri("export.precision","owned-output"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ExportFormat.RAW_FILE, File(home,"job").apply { mkdir() }, "", {}, {})
            ExportCaptureStore(File(home,"staging")).capture(permit).use { capture ->
                file.writeText("changed after sealed capture")
                val result = ExportEngine().transfer(capture, ExportFormat.RAW_FILE, destination)
                assertEquals(1L, result.entries)
                assertEquals(savedBytes.size.toLong(), result.bytes)
                assertArrayEquals(savedBytes, output.readBytes())
            }
        } finally { home.deleteRecursively() }
    }
}
