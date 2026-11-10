package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Application-only first registration; existing catalogs keep their current store and format. */
internal class DefaultProjectStartup(
    appFilesDir: File,
    private val modeGuard: DefaultCatalogModeGuard = DefaultCatalogModeGuard(appFilesDir),
    private val checkpoint: (String) -> Unit = {},
) {
    private val app = appFilesDir.canonicalFile
    private val metadata = File(app, ".droide")
    private val storage = File(app, "catalog-startup")
    private val slot = File(storage, "initial-project-registration")
    private val membership = File(storage, "catalog-membership.json")

    internal fun requireDefaultMode() = modeGuard.requireDefaultMode()

    private fun checkedCheckpoint(point: String) {
        requireDefaultMode()
        checkpoint(point)
        requireDefaultMode()
    }

    suspend fun prepare() = withContext(Dispatchers.IO) {
        WorkspaceFileMutationLocks.withRoot(app) { guard ->
            requireDefaultMode()
            val context = currentCoroutineContext()
            guard.requireRoot(app)
            val hasEvidence = AnchoredWorkspaceFiles(app).use { files ->
                val existing = files.kind(storage.name) != AnchoredWorkspaceFiles.Kind.ABSENT
                // Do not enroll or migrate a pre-existing installation merely by opening it.
                if (!existing && files.kind(".droide/projects.json") != AnchoredWorkspaceFiles.Kind.ABSENT) return@withRoot
                ensureDirectory(files, ".droide")
                ensureDirectory(files, "projects")
                if (!existing) {
                    // A missing catalog is not authority to adopt arbitrary project folders.
                    FileProjectStore(app).loadCatalog()
                    ensureDirectory(files, storage.name)
                }
                files.openDirectory(storage.name).use { owner ->
                    var populated = false
                    owner.forEachChild { populated = true }
                    if (!populated) {
                        val catalog = FileProjectStore(app).loadCatalog()
                        check(!catalog.revision.existed) { "Empty startup evidence cannot replace an existing registration" }
                    }
                    populated
                }
            }
            val bootstrap = CanonicalJournalBootstrap(slot, MAX_CATALOG_BYTES) { checkedCheckpoint("enrollment/$it") }
            val inspection = if (hasEvidence) {
                bootstrap.inspectWithMembership(metadata, InitialProjectRegistration.AUTHORITY_ID, membership, context)
            } else {
                bootstrap.createWithMembership(metadata, InitialProjectRegistration.AUTHORITY_ID, membership, context)
                bootstrap.inspectWithMembership(metadata, InitialProjectRegistration.AUTHORITY_ID, membership, context)
            }
            val registration = InitialProjectRegistration(app, inspection.enrollment, MAX_CATALOG_BYTES, ::checkedCheckpoint)
            if (inspection.history.pending == null && inspection.history.completed.isEmpty()) registration.prepare()
            // resume() completes only witnessed outcomes. It never replays an uncertain move.
            registration.resume()
            requireDefaultMode()
            context.ensureActive()
            guard.requireRoot(app)
        }
    }

    private fun ensureDirectory(files: AnchoredWorkspaceFiles, name: String) {
        when (files.kind(name)) {
            AnchoredWorkspaceFiles.Kind.ABSENT -> check(files.createDirectory(name).durable) {
                "Startup storage creation needs recovery: $name; existing project data was preserved"
            }
            AnchoredWorkspaceFiles.Kind.DIRECTORY -> files.directoryKey(name)
            else -> error("Startup storage is not an owned directory: $name")
        }
    }

    private companion object { const val MAX_CATALOG_BYTES = 1_000_000L }
}
