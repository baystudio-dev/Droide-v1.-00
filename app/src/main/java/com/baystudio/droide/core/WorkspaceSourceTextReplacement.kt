package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A runtime-only proposal admitted to the actual String-backed editor capability.
 * This is not permission, a disk write, or a promise of arbitrarily large editable documents.
 */
class WorkspaceSourceTextReplacement private constructor(
    internal val text: String,
    val utf8Bytes: Long,
    val utf16Chars: Long,
    val sha256: String,
) {
    @Volatile private var committed: Publication? = null
    @Volatile private var started = false
    @Volatile private var mutationEntered = false
    /** Entered immediately before native/model mutation. No receipt after this is an unknown effect. */
    val publicationAttempted: Boolean get() = mutationEntered
    internal fun enteringMutation() { check(started); mutationEntered = true }
    val publication: Publication? get() = committed

    /** Runtime proof captured inside the native editor publication callback. It proves that the
     * exact replacement was applied at this version; it does not assert no later user edit exists.
     */
    class Publication private constructor(
        val replacement: WorkspaceSourceTextReplacement,
        val path: String,
        val beforeSnapshot: WorkspaceDocumentSnapshot,
        val afterSnapshot: WorkspaceDocumentSnapshot,
    ) {
        val before: WorkspaceDocumentVersion get() = beforeSnapshot.version
        val after: WorkspaceDocumentVersion get() = afterSnapshot.version
        val effect: String get() = "LIVE_BUFFER"
        val sha256: String get() = replacement.sha256
        val utf8Bytes: Long get() = replacement.utf8Bytes
        val utf16Chars: Long get() = replacement.utf16Chars
        companion object {
            internal fun capture(replacement: WorkspaceSourceTextReplacement, path: String,
                expected: WorkspaceDocumentVersion, applied: WorkspaceDocumentMutationResult.Applied): Publication {
                require(applied.before.path == path && applied.after.path == path)
                require(expected.documentId != null && applied.before.version == expected)
                require(applied.after.documentId == expected.documentId)
                require(applied.after.content == replacement.text)
                require(applied.after.revision >= expected.revision && applied.after.changeVersion >= expected.changeVersion)
                return Publication(replacement, path, applied.before, applied.after)
            }
        }
    }

    internal fun requireUnused() = check(!started && committed == null) { "Source proposal was already attempted; do not repeat it" }
    @Synchronized internal fun <T> attempt(action: () -> T): T {
        requireUnused()
        started = true
        return action()
    }
    @Synchronized internal fun published(path: String, expected: WorkspaceDocumentVersion,
        applied: WorkspaceDocumentMutationResult.Applied) {
        check(started && committed == null) { "Source publication has no unique active attempt" }
        committed = Publication.capture(this, path, expected, applied)
    }

    companion object {
        /** Admission precedes allocation. Limits are the existing editor's full-authority profile,
         * not a new tool argument cap. Optimized/preview documents need a different native API.
         */
        internal suspend fun prepare(session: OwnedResponseContentStore.Session,
            source: StreamedFileArgumentExtractor.Source, checkCurrent: () -> Unit): WorkspaceSourceTextReplacement =
            withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                fun current() { caller.ensureActive(); checkCurrent() }
                current()
                require(source.kind == OwnedResponseContentStore.Kind.TOOL_CONTENT)
                require(source.bytes in 0..EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES &&
                    source.utf16Units in 0..EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES) {
                    "The current String-backed editor cannot safely apply this source size; no editor or disk content was changed"
                }
                val actual = session.openCompleted(source.handle, ::current).content
                require(actual.kind == source.kind && actual.byteLength == source.bytes &&
                    actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256) {
                    "Source proposal changed after admission"
                }
                val text = StringBuilder(source.utf16Units.toInt())
                var lines = 1
                session.openPages(source.handle, ::current).use { pages ->
                    while (true) {
                        current()
                        val page = pages.readPage() ?: break
                        require(text.length.toLong() + page.length <= source.utf16Units)
                        lines += page.count { it == '\n' }
                        require(lines <= EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES) {
                            "The current editor requires large-file mode for this source; no editor or disk content was changed"
                        }
                        text.append(page)
                    }
                }
                current(); require(text.length.toLong() == source.utf16Units)
                WorkspaceSourceTextReplacement(text.toString(), source.bytes, source.utf16Units, source.sha256)
            }
    }
}
