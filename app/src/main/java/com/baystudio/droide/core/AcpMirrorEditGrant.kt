package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** One exact approved text edit, carried only into its turn's immediate reconciliation. */
internal class AcpMirrorEditGrant(
    private val proposal: Proposal,
    private val isCurrent: () -> Boolean,
) {
    internal data class Proposal(
        val root: File,
        val canonicalRemotePath: String,
        val relativePath: String,
        val targetHash: String,
        val base: StreamFileVersion,
        val action: String,
        val toolCallId: String,
    )

    private val attempted = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val admitted = AtomicBoolean(false)
    val action: String get() = proposal.action
    val resource: String get() = proposal.relativePath

    suspend fun consume(remotePath: String, relativePath: String, target: WorkspaceSyncManifest.Entry?): Boolean {
        if (!attempted.compareAndSet(false, true)) return false
        if (revoked.get() || !isCurrent() || proposal.action != "write_file" || proposal.toolCallId.isBlank() ||
            proposal.canonicalRemotePath != remotePath || proposal.relativePath != relativePath ||
            target?.sha256 != proposal.targetHash ||
            target.mode != if (proposal.base.executable) 493 else 420) return false
        try {
            proposal.base.requireMatches(proposal.root, proposal.relativePath)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        if (revoked.get() || !isCurrent()) return false
        admitted.set(true)
        return true
    }

    fun requireCurrent() {
        check(admitted.get() && !revoked.get() && isCurrent()) { "Exact CLI edit approval is no longer current" }
        proposal.base.requireMetadataMatches(proposal.root, proposal.relativePath)
    }

    fun revoke() { revoked.set(true) }

    companion object {
        fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
