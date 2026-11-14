package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ACP text policy is separate from unrestricted-size streamed workspace artifacts. */
internal object AcpWorkspaceText {
    const val MAX_BYTES = 2_000_000L

    private data class Observation(val content: String?, val version: StreamFileVersion)

    suspend fun version(files: FileRepository, relative: String): StreamFileVersion = observe(files, relative).version

    private suspend fun observe(files: FileRepository, relative: String): Observation = withContext(Dispatchers.IO) {
        val file = PathSecurity.resolveWithin(files.root, relative)
        // Validate text before hashing: a text write must not replace an arbitrary binary
        // or scan a multi-GiB output just because the new content happens to be small.
        val content = if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file.toPath())) { "ACP text path cannot be a symbolic link" }
            files.readTextForEdit(relative, MAX_BYTES)
        } else null
        val version = StreamFileVersion.capture(files.root, relative)
        val expected = DiskFileVersion.fromText(content)
        check(version.existed == expected.existed && version.bytes == expected.bytes &&
            version.sha256 == expected.sha256) { "ACP text file changed during observation; existing data was preserved" }
        Observation(content, version)
    }

    suspend fun read(files: FileRepository, relative: String): String {
        val observed = observe(files, relative)
        check(observed.version.existed) { "ACP execution file is unavailable: $relative" }
        observed.version.requireMatches(files.root, relative)
        return requireNotNull(observed.content)
    }
}

/** A local projection token is captured before approval; it never grants write permission. */
internal interface AcpTextProjection {
    suspend fun requireUnchanged()
    suspend fun publish(content: String, requireCurrent: () -> Unit)
}
