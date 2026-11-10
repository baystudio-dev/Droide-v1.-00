package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWebPdfWorkflowTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private suspend fun withPdf(block: suspend (java.io.File,AgentWebResearch,WebFixturePdf,String) -> Unit) {
        val root=Files.createTempDirectory("droide-web-pdf-").toFile(); val pdf=WebFixturePdf()
        val web=AgentWebResearch(root,WebFixtureTransport { AgentWebResponse(200,"%PDF-1.4\nfixture".toByteArray(),it,"application/pdf") },pdf)
        try {
            val opened=web.open("owner","https://docs.example/manual.pdf",null,0,20,false,{_,_->},null)
            assertEquals(JsonPrimitive(2),opened["page_count"])
            block(root,web,pdf,opened.getValue("ref_id").jsonPrimitive.content)
        } finally { web.close(); PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root)); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun pdfPageTextHasSnapshotLinesAndSourceReference() = runBlocking { withPdf { _,web,pdf,id ->
        val page=web.readPdf("owner",id,1,0,10,{_,_->})
        assertEquals(id,page.getValue("pdf_ref_id").jsonPrimitive.content); assertEquals(JsonPrimitive("pdf_page"),page["kind"])
        assertTrue(page.getValue("url").jsonPrimitive.content.endsWith("#page=2")); assertEquals(listOf(1),pdf.pagesRead)
        val found=web.find("owner",page.getValue("ref_id").jsonPrimitive.content,"selectable",0,10,false,{_,_->})
        assertEquals(1,found.getValue("matches").jsonArray.size)
    } }
    @Test fun unavailableSelectableTextIsExplicitAndDoesNotInventPageContents() = runBlocking { withPdf { _,web,pdf,id ->
        pdf.selectableText=null
        val page=web.readPdf("owner",id,0,0,10,{_,_->})
        assertEquals(JsonPrimitive(false),page["text_available"]); assertFalse(page.containsKey("lines")); assertTrue(page.toString().contains("pdf_screenshot"))
    } }
    @Test fun screenshotAttachesRealPngToTheBoundModelSink() = runBlocking { withPdf { root,web,_,id ->
        var bytes:ByteArray?=null
        val result=web.screenshotPdf("owner",id,0,{_,_->},{ file -> bytes=file.readBytes(); true })
        assertArrayEquals(webFixturePng,bytes); assertEquals(JsonPrimitive(true),result["image_input_attached"])
        assertEquals(JsonPrimitive(1),result["width"]); assertTrue(java.io.File(result.getValue("path").jsonPrimitive.content).isFile)
        assertFalse(PathSecurity.contains(root, java.io.File(result.getValue("path").jsonPrimitive.content)))
    } }
    @Test fun screenshotWithoutVisionIsCaptureNotInterpretation() = runBlocking { withPdf { _,web,_,id ->
        val result=web.screenshotPdf("owner",id,0,{_,_->},null)
        assertEquals(JsonPrimitive(false),result["image_input_attached"]); assertTrue(result.toString().contains("no image data"))
    } }
    @Test fun pageBoundsPermissionAndSnapshotTamperingStopTheBackend() = runBlocking { withPdf { root,web,pdf,id ->
        assertTrue(runCatching { web.readPdf("owner",id,2,0,10,{_,_->}) }.isFailure)
        assertTrue(runCatching { web.screenshotPdf("owner",id,0,{_,_->throw AgentWebPermissionDenied("DENIED")},null) }.exceptionOrNull() is AgentWebPermissionDenied)
        java.io.File(ChatMediaStoragePaths.directory(root), "scratch/web").walkTopDown().first { it.extension=="pdf" }.writeText("tampered")
        assertTrue(runCatching { web.readPdf("owner",id,0,0,10,{_,_->}) }.exceptionOrNull()!!.message!!.contains("changed"))
        assertTrue(pdf.pagesRead.isEmpty())
    } }
    @Test fun pixelAndEncodedOutputLimitsProtectMobileMemory() {
        listOf(1 to 1,40000 to 60000,1000000 to 1,1000 to 2000).forEach { (w,h) ->
            val size=AndroidAgentWebPdf.boundedSize(w,h)
            assertTrue(size.width>0 && size.height>0); assertTrue(maxOf(size.width,size.height)<=1600)
            assertTrue(size.width.toLong()*size.height<=2_000_000)
        }
        val output=ByteArrayOutputStream(); val limited=LimitedOutputStream(output,10)
        limited.write(ByteArray(10)); assertThrows(IllegalArgumentException::class.java) { limited.write(0) }; assertEquals(10,output.size())
    }
    @Test fun downloadedImageUsesTheNativeAgentImageCallback() = runBlocking {
        val root=Files.createTempDirectory("droide-web-image-").toFile()
        val web=AgentWebResearch(root,WebFixtureTransport { AgentWebResponse(200,webFixturePng,it,"image/png") },WebFixturePdf())
        try {
            val images=AgentToolImages(); val ctx=agentWorkflowContext(root,imageSink=images::add,webResearch=web)
            val result=Json.parseToJsonElement(AgentTools.execute("web",buildJsonObject { put("operation","open"); put("url","https://docs.example/image.png") },ctx)).jsonObject
            assertEquals(JsonPrimitive(true),result["image_input_attached"]); assertEquals(1,images.refs().size)
            assertEquals("image/png",images.refs().single().mediaType)
        } finally { web.close(); PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root)); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun partialBinaryDownloadsCannotBeCalledReadablePdfOrImage() = runBlocking {
        val root=Files.createTempDirectory("droide-web-partial-").toFile()
        val web=AgentWebResearch(root,WebFixtureTransport { AgentWebResponse(200,webFixturePng,it,"image/png",truncated=true) },WebFixturePdf())
        try { assertTrue(runCatching { web.open("owner","https://docs.example/image.png",null,0,10,false,{_,_->},null) }.isFailure) }
        finally { web.close(); PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root)); PathSecurity.deleteTreeNoFollow(root) }
    }
}
