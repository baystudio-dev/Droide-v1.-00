package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.io.File

// Process-wide projection of integrity-verified, data-only extensions.


object DeclarativeExtensionRuntime {
    private const val URI_COPY_BUFFER = 64 * 1024
    private var store: DeclarativeExtensionStore? = null
    private var appContext: Context? = null
    private val activeIds = linkedSetOf<String>()
    private var installedProjectionsSuspended = false
    private var verifiedRecords: List<InstalledDeclarativeExtensionRecord> = emptyList()

    @Synchronized
    fun initialize(context: Context, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        if (store == null) {
            appContext = context.applicationContext
            store = DeclarativeExtensionStore(context.applicationContext)
        }
        reloadLocked(checkCancelled)
        installedProjectionsSuspended = false
    }

    @Synchronized
    fun records(): List<InstalledDeclarativeExtensionRecord> = verifiedRecords.toList()

    @Synchronized
    fun manifests(): List<DroideExtensionManifest> = verifiedRecords.map(InstalledDeclarativeExtensionRecord::manifest)

    @Synchronized
    fun install(vsix: File): InstalledDeclarativeExtensionRecord {
        requireInstalledProjectionsEnabled()
        val targetStore = store ?: error("Declarative extension runtime is not initialized")
        val record = targetStore.install(vsix)
        reloadLocked()
        return record
    }

    @Synchronized
    fun installFromUri(uri: Uri): InstalledDeclarativeExtensionRecord {
        requireInstalledProjectionsEnabled()
        val context = appContext ?: error("Declarative extension runtime is not initialized")
        val temp = File(context.cacheDir, "declarative-vsix-${System.nanoTime()}.vsix")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open VSIX")
            input.buffered().use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(URI_COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= DeclarativeVsixParser.MAX_VSIX_BYTES) { "VSIX exceeds safe import limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return install(temp)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun uninstall(extensionId: String) {
        requireInstalledProjectionsEnabled()
        val targetStore = store ?: error("Declarative extension runtime is not initialized")
        targetStore.uninstall(extensionId)
        reloadLocked()
    }

    private fun reloadLocked(checkCancelled: () -> Unit = {}) {
        val targetStore = store ?: return
        // Verification is read-only. Cancellation must leave the current projections intact.
        val verified = targetStore.listVerified(checkCancelled).sortedBy { it.extensionId }
        checkCancelled()
        activeIds.toList().forEach(::unregisterProjection)
        activeIds.clear()
        verified.forEach { record -> registerVerifiedProjection(record, targetStore.installRoot(record)) }
        verifiedRecords = verified
    }

    @Synchronized
    fun suspendInstalledProjections() {
        installedProjectionsSuspended = true
        verifiedRecords = emptyList()
        activeIds.toList().forEach { extensionId ->
            unregisterProjection(extensionId)
            activeIds.remove(extensionId)
        }
    }

    // The same production registration path owns partial effects before it can fail.
    @Synchronized
    internal fun registerVerifiedProjection(
        record: InstalledDeclarativeExtensionRecord,
        root: File,
        afterLanguageRegistered: () -> Unit = {},
    ) {
        activeIds += record.extensionId
        val manifest = record.manifest
        LanguageRegistry.registerDeclarative(
            record.extensionId,
            manifest.contributes.languages.map { language ->
                DeclarativeLanguageSpec(
                    id = language.id,
                    name = language.aliases.firstOrNull() ?: language.id,
                    extensions = language.extensions.toList(),
                    filenames = language.filenames.toList(),
                )
            },
        )
        afterLanguageRegistered()
        DeclarativeSnippetRegistry.register(record.extensionId, root, manifest.contributes.snippets)
        DeclarativeTextMateRegistry.register(record.extensionId, root, manifest.contributes.languages, manifest.contributes.grammars)
        DeclarativeThemeRegistry.register(record.extensionId, root, manifest.contributes.themes)
    }

    private fun requireInstalledProjectionsEnabled() {
        check(!installedProjectionsSuspended) {
            "Installed extensions are temporarily unavailable in built-in startup recovery. Start a fresh app process to retry normal startup."
        }
    }

    private fun unregisterProjection(extensionId: String) {
        LanguageRegistry.unregisterDeclarative(extensionId)
        DeclarativeSnippetRegistry.unregister(extensionId)
        DeclarativeTextMateRegistry.unregister(extensionId)
        DeclarativeThemeRegistry.unregister(extensionId)
    }
}
