package com.baystudio.droide.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baystudio.droide.MainActivity
import com.baystudio.droide.core.ExitUiWorkOwner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Authored Compose device cases. No claim of execution without an attached device/emulator. */
@RunWith(AndroidJUnit4::class)
class DocumentPreviewLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun fixture(file: File, text: String) {
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>""",
        )
        ZipOutputStream(file.outputStream()).use { out -> parts.forEach { (name, body) ->
            out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray()); out.closeEntry()
        } }
    }
    @Test fun stopDuringOpeningClearsSpinnerAndExitAwaitsCleanup() {
        val file = File.createTempFile("docx-lifecycle", ".docx", compose.activity.cacheDir)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = ExitUiWorkOwner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fixture(file, "Test body")
            compose.runOnUiThread { compose.activity.setContent {
                CompositionLocalProvider(LocalExitUiWorkOwner provides owner) {
                    DocumentPreviewPane(file, "test.docx") {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); file
                    }
                }
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Stop").performClick()
            compose.onNodeWithText("Preview stopped.").assertExists()
            val stopping = scope.async { owner.stopAndAwait() }
            Thread.sleep(100); assertFalse(stopping.isCompleted)
            release.countDown()
            runBlocking { withTimeout(5_000) { stopping.await() } }
            compose.onNodeWithText("Preview stopped.").assertExists()
        } finally { release.countDown(); scope.cancel(); file.delete() }
    }
    @Test fun replacementWorkspaceIdentityAtSamePathReloadsText() {
        val file = File.createTempFile("docx-replace", ".docx", compose.activity.cacheDir)
        var identity by mutableStateOf<Any>(Any())
        try {
            fixture(file, "First workspace body")
            compose.runOnUiThread { compose.activity.setContent { DocumentPreviewPane(identity, "same.docx") { file } } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("First workspace body", substring = true).fetchSemanticsNodes().isNotEmpty() }
            fixture(file, "Second workspace body")
            compose.runOnUiThread { identity = Any() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Second workspace body", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("First workspace body", substring = true).assertDoesNotExist()
        } finally { file.delete() }
    }
}
