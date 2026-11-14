package com.baystudio.droide.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*


internal class LspDocumentSyncAuthority(
    private val rpc: JsonRpcProcess,
    private val policy: LspTextSyncPolicy,
    private val forceInitialFullChange: Boolean = false,
) {
    private val versions = ConcurrentHashMap<String, Int>()
    private val openUris = ConcurrentHashMap.newKeySet<String>()
    private val deliveredText = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()

    init {
        require(!forceInitialFullChange || (policy.openClose && policy.change == LspTextSyncPolicy.ChangeKind.FULL)) {
            "Forced initial reload requires open/close and full document synchronization"
        }
    }

    fun isOpen(uri: String): Boolean = uri in openUris
    fun version(uri: String): Int? = versions[uri]
    fun text(uri: String): String? = deliveredText[uri]

    suspend fun synchronize(uri: String, languageId: String, text: String) =
        mutex.withLock { synchronizeLocked(uri, languageId, text) }

    suspend fun save(uri: String, languageId: String, text: String) = saveSnapshot(uri, languageId, text, text)

    suspend fun saveSnapshot(uri: String, languageId: String, savedText: String, currentText: String) = mutex.withLock {
        synchronizeLocked(uri, languageId, currentText)
        withContext(NonCancellable) {
            // didSave includes the actual persisted snapshot. The server's incremental
            // baseline remains the newer live buffer delivered by synchronizeLocked.
            LspProtocolContract.save(rpc, policy, uri, languageId, savedText, versions, openUris)
        }
    }

    suspend fun close(uri: String): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            val closed = LspProtocolContract.close(rpc, policy, uri, versions, openUris)
            if (closed) deliveredText.remove(uri)
            closed
        }
    }

    private suspend fun synchronizeLocked(uri: String, languageId: String, text: String) {
        if (uri !in openUris) {
            withContext(NonCancellable) {
                if (forceInitialFullChange) {
                    // PSES may already cache a disk-backed ScriptFile. A real same-text
                    // full reload is required even after didOpen; no fabricated edit.
                    // Do not publish any tracked ledger until BOTH writes committed.
                    rpc.notify("textDocument/didOpen", buildJsonObject {
                        put("textDocument", buildJsonObject {
                            put("uri", uri); put("languageId", languageId)
                            put("version", 1); put("text", text)
                        })
                    })
                    rpc.notify("textDocument/didChange", buildJsonObject {
                        put("textDocument", buildJsonObject { put("uri", uri); put("version", 2) })
                        put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", text) }) })
                    })
                    deliveredText[uri] = text
                    versions[uri] = 2
                    openUris.add(uri)
                } else {
                    LspProtocolContract.ensureTracked(rpc, policy, uri, languageId, text, versions, openUris)
                    deliveredText[uri] = text
                }
            }
            return
        }
        val delivered = deliveredText[uri] ?: error("Missing delivered LSP snapshot for tracked document $uri")
        require(delivered == text || policy.change != LspTextSyncPolicy.ChangeKind.NONE) {
            "Language server opted out of document changes; the current buffer cannot be synchronized. Restart the language server after saving."
        }
        if (delivered != text) {
            withContext(NonCancellable) {
                LspProtocolContract.change(rpc, policy, uri, languageId, delivered, text, versions, openUris)
                deliveredText[uri] = text
            }
        }
    }
}
