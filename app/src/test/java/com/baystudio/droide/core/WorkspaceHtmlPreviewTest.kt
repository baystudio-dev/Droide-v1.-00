package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceHtmlPreviewTest {
    private fun withRoot(block: (File, WorkspaceHtmlPreview) -> Unit) {
        val root = Files.createTempDirectory("droide-html-preview-").toFile()
        try { block(root, WorkspaceHtmlPreview(root)) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun WorkspaceHtmlPreview.asset(path: String): String = URI("https", null, host, -1, "/$path", null, null).toASCIIString()
    private fun WorkspaceHtmlPreview.Resource.text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun htmlJavascriptCssAndImageResourcesHaveARealSameOrigin() = withRoot { root, preview ->
        val assets = mapOf("index.html" to ("text/html" to "<script src='app.js'></script>"),
            "app.js" to ("application/javascript" to "document.body.textContent='Hello'"),
            "style.css" to ("text/css" to "body { color: red; }"),
            "picture.svg" to ("image/svg+xml" to "<svg></svg>"))
        for ((name, content) in assets) {
            root.resolve(name).writeText(content.second)
            val response = preview.open(preview.asset(name))
            assertEquals(200, response.status); assertEquals(content.first, response.mime)
            assertEquals(content.second, response.text())
            assertEquals("no-store", response.headers["Cache-Control"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
        assertTrue(preview.urlFor("index.html").startsWith("https://"))
        assertTrue(preview.owns(preview.urlFor("index.html")))
    }

    @Test fun nestedNamesSpacesUnicodeAndQueriesResolveToTheOriginalFile() = withRoot { root, preview ->
        root.resolve("nested").mkdir()
        root.resolve("nested/halaman ü.html").writeText("local")
        val url = preview.urlFor("nested/halaman ü.html")
        assertEquals("local", preview.open("$url?reload=123#section").text())
    }

    @Test fun privateFilesAndMetadataAreNeverServed() = withRoot { root, preview ->
        for (name in listOf(".env", ".env.production", ".npmrc", "local.properties", "credentials.json", ".git/config", ".droide/state.json", "key.pem")) {
            root.resolve(name).apply { checkNotNull(parentFile).mkdirs(); writeText("private") }
            val response = preview.open(preview.asset(name))
            assertEquals(name, 403, response.status)
            assertFalse(response.text().contains("private"))
        }
    }

    @Test fun symlinkAliasesToPrivateFilesRemainPrivate() = withRoot { root, preview ->
        root.resolve(".env").writeText("private")
        Files.createSymbolicLink(root.resolve("public.txt").toPath(), root.resolve(".env").toPath())
        assertEquals(403, preview.open(preview.asset("public.txt")).also { it.body.close() }.status)
    }

    @Test fun traversalAndSymlinksCannotEscapeTheWorkspace() = withRoot { root, preview ->
        val outside = Files.createTempDirectory("droide-html-outside-").toFile()
        try {
            outside.resolve("secret.txt").writeText("outside")
            Files.createSymbolicLink(root.resolve("outside").toPath(), outside.toPath())
            for (path in listOf("../../secret.txt", "%2e%2e/%2e%2e/secret.txt", "outside/secret.txt", "x%5c..%5csecret.txt")) {
                val response = preview.open("https://${preview.host}/$path")
                assertEquals(path, 403, response.status); response.body.close()
            }
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun wrongOriginSchemePortAndUserInfoDoNotReadProjectFiles() = withRoot { root, preview ->
        root.resolve("index.html").writeText("project")
        for (url in listOf("http://${preview.host}/index.html", "https://${preview.host}:8443/index.html",
            "https://user@${preview.host}/index.html", "https://example.com/index.html", "file://${root.path}/index.html")) {
            val response = preview.open(url)
            assertEquals(url, 403, response.status); assertFalse(response.text().contains("project"))
        }
    }

    @Test fun workspacesUseSeparateOrigins() = withRoot { _, first ->
        withRoot { _, second -> assertNotEquals(first.host, second.host); assertFalse(first.owns(second.asset("index.html"))) }
    }

    @Test fun unsupportedMethodsCannotWriteFiles() = withRoot { root, preview ->
        val file = root.resolve("index.html").apply { writeText("original") }
        for (method in listOf("POST", "PUT", "DELETE")) {
            val response = preview.open(preview.asset("index.html"), method)
            assertEquals(405, response.status); response.body.close()
        }
        assertEquals("original", file.readText())
    }

    @Test fun missingFilesAreFiniteFailuresAndNeverBecomeNetworkRequests() = withRoot { _, preview ->
        val response = preview.open(preview.asset("missing.js"))
        assertEquals(404, response.status); response.body.close()
    }

    @Test fun hugeAssetsAreRejectedWithoutReadingTheirBodies() = withRoot { root, preview ->
        RandomAccessFile(root.resolve("huge.mp4"), "rw").use { it.setLength(64L * 1024L * 1024L + 1L) }
        val response = preview.open(preview.asset("huge.mp4"))
        assertEquals(413, response.status); response.body.close()
    }

    @Test fun previewEntryRequiresAnExistingHtmlFile() = withRoot { root, preview ->
        root.resolve("readme.md").writeText("text")
        assertTrue(runCatching { preview.urlFor("readme.md") }.isFailure)
        assertTrue(runCatching { preview.urlFor("missing.html") }.isFailure)
        root.resolve("page.HTM").writeText("<html></html>")
        assertTrue(preview.urlFor("page.HTM").endsWith("page.HTM"))
    }

    @Test fun mediaRangeReturnsOnlyTheRequestedOriginalBytes() = withRoot { root, preview ->
        root.resolve("song.mp3").writeText("0123456789")
        val response = preview.open(preview.asset("song.mp3"), range = "bytes=2-5")
        assertEquals(206, response.status); assertEquals("audio/mpeg", response.mime)
        assertEquals("bytes 2-5/10", response.headers["Content-Range"])
        assertEquals("4", response.headers["Content-Length"])
        assertEquals("2345", response.text())
    }

    @Test fun suffixAndOpenEndedRangesSupportMediaSeeking() = withRoot { root, preview ->
        root.resolve("song.mp3").writeText("0123456789")
        assertEquals("789", preview.open(preview.asset("song.mp3"), range = "bytes=-3").text())
        assertEquals("789", preview.open(preview.asset("song.mp3"), range = "bytes=7-").text())
        assertEquals("789", preview.open(preview.asset("song.mp3"), range = "bytes=7-99").text())
    }

    @Test fun invalidRangesReturn416AndDoNotExposeAnyAssetBytes() = withRoot { root, preview ->
        root.resolve("song.mp3").writeText("0123456789")
        for (range in listOf("bytes=10-", "bytes=5-1", "bytes=-0", "bytes=0-1,3-4", "items=1-2", "bytes=999999999999999999999999-")) {
            val response = preview.open(preview.asset("song.mp3"), range = range)
            assertEquals(range, 416, response.status)
            assertEquals("bytes */10", response.headers["Content-Range"])
            response.body.close()
        }
    }

    @Test fun headDoesNotReturnAnAssetBody() = withRoot { root, preview ->
        root.resolve("song.mp3").writeText("0123456789")
        val response = preview.open(preview.asset("song.mp3"), method = "HEAD", range = "bytes=2-5")
        assertEquals(206, response.status); assertEquals("4", response.headers["Content-Length"])
        assertEquals("", response.text())
    }
}
