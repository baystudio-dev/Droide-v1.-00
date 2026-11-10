package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.util.Base64

internal val webFixturePng: ByteArray get() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")

/** Host fixture, not proof of Android PdfRenderer execution. */
internal class WebFixturePdf(var selectableText: String? = "PDF first page\nselectable evidence") : AgentWebPdfBackend {
    val pagesRead = mutableListOf<Int>()
    override fun pageCount(file: File) = 2
    override fun text(file: File, page: Int): String? { pagesRead += page; return selectableText }
    override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize {
        pagesRead += page; output.write(webFixturePng); return AgentWebImageSize(1,1)
    }
}

internal class WebFixtureTransport(private val handler: suspend (String) -> AgentWebResponse) : AgentWebTransport {
    val requests = mutableListOf<String>()
    override suspend fun get(url: String, beforeRequest: suspend (String) -> Unit): AgentWebResponse {
        beforeRequest(url); requests += url; return handler(url)
    }
}

internal fun webHtml(url: String, body: String, truncated: Boolean = false) = AgentWebResponse(200,body.toByteArray(),url,truncated=truncated)
internal fun webSearchHtml(url: String = "https://example.com/source") = "<div class='result'><a class='result__a' href='$url'>Primary source</a><div class='result__snippet'>Discovery snippet</div></div>"
