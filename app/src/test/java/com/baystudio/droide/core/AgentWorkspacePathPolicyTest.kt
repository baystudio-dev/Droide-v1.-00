package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspacePathPolicyTest {
    @Test fun ordinaryBinaryFormatsAndPublishedBuildProductsHaveNoExtensionWhitelist() {
        for (path in listOf(
            "kucing.glb", "preview.png", "movie.mp4", "source.zip", "document.pdf",
            "application.apk", "opaque.custom-format", "file name with spaces.bin",
            "app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/bundle/release/app.aab",
            "build/libs/application.jar", "build/reports/tests/index.html", "build/generated/source.java",
            "other/intermediates/real-source.bin", "tmp/real-source.bin",
        )) assertTrue("Ordinary project path rejected: $path", AgentWorkspacePathPolicy.visible(path))
    }

    @Test fun privateStateDependenciesIntermediatesAndUnsafePathsStayExcluded() {
        for (path in listOf(
            ".git/config", "module/.git/config", ".gradle/cache", ".droide/cache",
            "module/node_modules/lib/main.js", "app/build/intermediates/result.bin",
            "build/tmp/work.bin", "app/build/kotlin/cache.bin", "app/build/.transforms/result.jar",
            ".env", "config/.env.production", "keys/private.pem", "local.properties",
            ".droide-sync-manifest.tsv", ".droide-tmp-123", "assets/.droide-copy-123.tmp", "../escape.apk", "assets/../escape.png",
            "/absolute.apk", "assets\\model.glb", "assets//model.glb", "./model.glb",
            "line\nbreak.bin", "tab\tname.bin", "null\u0000name.bin", "control\u007fname.bin", "",
        )) assertFalse("Unsafe or excluded path admitted: $path", AgentWorkspacePathPolicy.visible(path))
    }

    @Test fun manifestAndChecksumParsingKeepBinaryOutputPathsAndApplyTheSamePolicy() {
        val hash = "a".repeat(64)
        val visible = listOf("model.glb", "preview.png", "app/build/outputs/apk/debug/app-debug.apk")
        val excluded = listOf("app/build/intermediates/o", ".env", "node_modules/x")
        val rows = (visible + excluded).joinToString("") { "$hash\t420\t$it\n" }
        val checksum = (visible + excluded).joinToString("") { "$hash  ./$it\n" }
        assertEquals(visible.associateWith { hash }, AgentWorkspacePathPolicy.manifest(rows))
        assertEquals(visible.associateWith { hash }, AgentWorkspacePathPolicy.hashListing(checksum))
    }

    @Test fun actualHashScanKeepsApkOutputAndPrunesOnlyItsTransientBuildSiblings() {
        val root = Files.createTempDirectory("agent scan quote ' space-").toFile()
        try {
            val included = listOf("image preview.png", "app/build/outputs/apk/debug/app-debug.apk", "build/libs/app.jar")
            val excluded = listOf("app/build/intermediates/object.o", "app/build/tmp/data", "app/build/kotlin/cache",
                "app/build/.transforms/cache", ".droide-tmp-123", "assets/.droide-copy-123.tmp", "node_modules/index.js", ".git/config", ".droide/state", "local.properties")
            (included + excluded).forEach { name ->
                File(root, name).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, -1, 2)) }
            }
            val command = AgentWorkspacePathPolicy.hashScanCommand(root.absolutePath, "sha256sum")
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Host checksum command did not exit", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(output, 0, process.exitValue())
            assertEquals(included.toSet(), AgentWorkspacePathPolicy.hashListing(output).keys)
            excluded.forEach { assertTrue("Inspection must not remove work", File(root, it).exists()) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
