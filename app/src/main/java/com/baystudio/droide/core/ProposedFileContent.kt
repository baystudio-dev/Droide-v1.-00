package com.baystudio.droide.core

import java.io.OutputStream

/** Runtime-only exact proposed content. A preview is never presented as the whole file.
 * Local capability stays inside the app; not serialized into project/session/provider JSON.
 */
class ProposedFileContent private constructor(
    private val session: OwnedResponseContentStore.Session,
    private val source: StreamedFileArgumentExtractor.Source,
    private val checkOwner: () -> Unit,
) {
    val byteLength: Long get() = source.bytes
    val sha256: String get() = source.sha256
    internal data class Page(val text: String, val hasNext: Boolean)
    internal fun verify(checkCancelled: () -> Unit = {}) {
        checkOwner(); checkCancelled()
        val actual = session.openCompleted(source.handle, checkCancelled).content
        check(actual.kind == source.kind && actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256)
        checkOwner(); checkCancelled()
    }
    internal fun readPage(index: Int, checkCancelled: () -> Unit = {}): Page {
        require(index >= 0 && index < Int.MAX_VALUE)
        verify(checkCancelled)
        return session.openPages(source.handle, checkCancelled).use { reader ->
            var text: String? = null
            repeat(index + 1) { checkOwner(); checkCancelled(); text = reader.readPage() }
            checkOwner(); checkCancelled()
            val more = reader.readPage() != null
            checkOwner(); checkCancelled()
            Page(text.orEmpty(), more)
        }
    }
    internal fun export(output: OutputStream, checkCancelled: () -> Unit = {}) {
        verify(checkCancelled)
        session.openBytes(source.handle, checkCancelled).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkOwner(); checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                check(n > 0) { "Proposed content made no progress" }
                output.write(buffer, 0, n)
            }
        }
        checkOwner(); checkCancelled()
    }
    companion object {
        internal fun admit(session: OwnedResponseContentStore.Session,
            source: StreamedFileArgumentExtractor.Source, checkOwner: () -> Unit): ProposedFileContent {
            require(source.kind == OwnedResponseContentStore.Kind.TOOL_CONTENT)
            return ProposedFileContent(session, source, checkOwner).also { it.verify() }
        }
    }
    override fun toString() = "ProposedFileContent(bytes=$byteLength, content=redacted)"
}
