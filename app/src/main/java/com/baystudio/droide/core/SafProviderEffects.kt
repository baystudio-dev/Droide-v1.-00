package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Collections
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Concrete SAF calls only. Production queries retain SafDocumentsTreeQuery's complete-cursor checks. */
internal interface SafProviderCalls {
    fun read(id: String, checkActive: () -> Unit): SafDocumentNode?
    fun children(id: String, maximum: Int, checkActive: () -> Unit): List<SafDocumentNode>
    fun input(uri: Uri): InputStream?
    fun output(uri: Uri): OutputStream?
    fun create(parent: Uri, mime: String, name: String): Uri?
    fun delete(uri: Uri): Boolean
}

/** Unwired executor. It never restores, retries, renames, clears evidence, or publishes a manifest. */
internal class SafProviderEffects(
    private val guard: WorkspaceMutationGuard,
    private val journal: SafOperationJournal,
    private val operation: SafOperationJournal.Operation,
    private val calls: SafProviderCalls,
    private val checkActive: () -> Unit,
) {
    private val tree = Uri.parse(journal.anchor.treeUri)
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val prepared = arrayListOf<Effect>()
    private val replacementCreates = hashMapOf<String, SafEffectPlan>()
    private var started = false
    private var failed = false
    private val inUse = AtomicBoolean(false)
    private var capturedBytes = 0L
    private var capturedFiles = 0
    private var capturedEntries = 0

    internal class Effect internal constructor(internal val owner: Any, internal val action: SafOperationJournal.Action)
    internal data class CapturedTree(val reference: SafEvidenceFile, val image: SafTreeEvidence)

    init {
        guard.requireRoot(journal.workspace)
        require(tree == DocumentsContract.buildTreeDocumentUri(tree.authority, rootId)) { "Noncanonical SAF tree binding" }
        check(operation.activeInvocation)
        journal.validate(operation, checkActive)
        requirePlanningPhase()
    }

    /** Captures all bytes and empty directories, then rechecks complete membership/content before returning. */
    fun captureBeforeTree(path: String, original: SafOwnedDocument): CapturedTree = owned {
        check(!started)
        requirePlanningPhase()
        requirePath(path)
        require(resolve(path) == original) { "SAF original identity changed before capture" }
        val entries = arrayListOf<SafTreeEvidenceEntry>()
        val seen = hashSetOf<String>()
        var encodedBytes = SafJournalCodec.encode(SafTreeEvidence.serializer(), SafTreeEvidence(
            operationId = operation.id, treeUri = tree.toString(), rootPath = path,
            rootDocument = original, entries = emptyList())).size.toLong()
        val queue = ArrayDeque<Pair<String, SafOwnedDocument>>()
        var listedEntries = 0
        var listedMetadata = 0L
        fun enqueue(relative: String, document: SafOwnedDocument) {
            checkActive()
            check(++listedEntries <= journal.limits.treeEntries)
            listedMetadata = SafOperationJournal.boundedAdd(listedMetadata,
                SafJournalCodec.encode(SafOwnedDocument.serializer(), document).size.toLong() + relative.toByteArray(Charsets.UTF_8).size,
                journal.limits.treeIndexBytes)
            queue.addLast(relative to document)
        }
        enqueue("", original)
        while (queue.isNotEmpty()) {
            val (relative, document) = queue.removeFirst()
            checkActive()
            val full = path + if (relative.isEmpty()) "" else "/$relative"
            require(full.count { it == '/' } + 1 <= journal.limits.treeDepth)
            check(++capturedEntries <= journal.limits.treeEntries) { "SAF capture entry capacity exceeded" }
            check(seen.add(document.documentId)) { "SAF capture repeats an identity" }
            val payload = if (document.mimeType == SafOperationJournal.DIRECTORY_MIME) null else {
                check(++capturedFiles <= journal.limits.treeFiles) { "SAF capture file capacity exceeded" }
                val input = requireNotNull(calls.input(Uri.parse(document.uri))) { "SAF beforeimage input unavailable" }
                journal.preserveInput(operation, input, journal.limits.treeBytes - capturedBytes, checkActive).also {
                    capturedBytes = SafOperationJournal.boundedAdd(capturedBytes, it.identity.bytes, journal.limits.treeBytes)
                }
            }
            val entry = SafTreeEvidenceEntry(relative, document, payload)
            encodedBytes = SafOperationJournal.boundedAdd(encodedBytes,
                SafJournalCodec.encode(SafTreeEvidenceEntry.serializer(), entry).size.toLong() + (if (entries.isEmpty()) 0 else 1),
                journal.limits.treeIndexBytes)
            entries += entry
            if (payload == null) children(document).sortedBy { it.name }.forEach {
                enqueue(if (relative.isEmpty()) it.name else "$relative/${it.name}", it)
            }
        }
        val image = SafTreeEvidence(operationId = operation.id, treeUri = tree.toString(), rootPath = path,
            rootDocument = original, entries = Collections.unmodifiableList(entries.sortedBy { checkActive(); it.relativePath }))
        // Encoding and all payload reservations are admitted before any destructive effect can be prepared.
        val ref = journal.preserveTreeIndex(operation, image, checkActive)
        verifyTree(image)
        CapturedTree(ref, image)
    }

    /** All actions must be prepared before the first execution; linked writes refer to a prepared creation. */
    fun prepare(plan: SafEffectPlan): Effect = owned {
        check(!started)
        requirePlanningPhase()
        require(plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE, SafEffectKind.DELETE))
        requirePath(plan.path)
        val parentPath = plan.path.substringBeforeLast('/', "")
        if (plan.parentCreationActionId == null) require(resolve(parentPath) == plan.parent) { "SAF parent binding changed" }
        else journal.admitParentChain(operation, plan, requireVerified = false, checkActive)
        if (plan.kind == SafEffectKind.CREATE_FILE || plan.kind == SafEffectKind.CREATE_DIRECTORY) {
            // A wrong creation result may affect its parent's namespace. Reserve it before the call.
            require(operation.scopes.any { SafOperationJournal.contains(it, parentPath) }) { "SAF creation requires its parent namespace scope" }
        }
        Effect(this, journal.prepare(operation, plan, checkActive)).also { prepared += it }
    }

    fun createFile(effect: Effect): SafOwnedDocument = requireNotNull(execute(effect, SafEffectKind.CREATE_FILE))
    fun createDirectory(effect: Effect): SafOwnedDocument = requireNotNull(execute(effect, SafEffectKind.CREATE_DIRECTORY))
    fun writeFile(effect: Effect): SafOwnedDocument = requireNotNull(execute(effect, SafEffectKind.WRITE_FILE))
    fun deleteTree(effect: Effect) { execute(effect, SafEffectKind.DELETE) }
    fun actionId(effect: Effect): String { require(effect.owner === this); return effect.action.id }

    private fun execute(effect: Effect, kind: SafEffectKind): SafOwnedDocument? = owned {
        require(effect.owner === this && effect in prepared && effect.action.admittedPlan.kind == kind)
        if (!started) {
            // Prevent an unseen/unprepared provider action from escaping the complete pre-effect pass.
            requireCompletedLocalActions()
            check(operation.effects.filterValues { it.plan.kind in PROVIDER_KINDS }.keys == prepared.map { it.action.id }.toSet())
            validatePlanSet()
            prepared.forEach { verifyBefore(it.action.admittedPlan, allowPendingCreation = true) }
            started = true
        }
        val plan = effect.action.admittedPlan
        journal.attempting(effect.action, checkActive)
        // ATTEMPTING deeply rereads private evidence/manifest. Recheck the provider AFTER that window,
        // immediately before the original call; refusal retains the honest attempted/uncertain record.
        verifyBefore(plan, allowPendingCreation = false)
        checkActive() // Cancellation after the durable attempt still leaves honest uncertainty.
        val raw = try {
            when (kind) {
                SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> SafProviderReturn(SafProviderReturnShape.URI,
                    uri = calls.create(Uri.parse(journal.admittedParent(operation, plan).uri), requireNotNull(plan.requestedMimeType), plan.path.substringAfterLast('/'))?.toString())
                SafEffectKind.DELETE -> SafProviderReturn(SafProviderReturnShape.BOOLEAN,
                    boolean = calls.delete(Uri.parse(requireNotNull(plan.original).uri)))
                SafEffectKind.WRITE_FILE -> {
                    val target = target(plan)
                    val ref = requireNotNull(plan.intended)
                    Files.newInputStream(journal.file(operation, ref.name).toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                        requireNotNull(calls.output(Uri.parse(target.uri))) { "SAF output unavailable" }.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val n = readSafChunk(input, buffer, checkActive)
                                check(n in -1..buffer.size)
                                if (n < 0) break
                                count = SafOperationJournal.boundedAdd(count, n.toLong(), ref.identity.bytes)
                                output.write(buffer, 0, n)
                                checkActive()
                            }
                            check(count == ref.identity.bytes) { "SAF intended payload changed during transfer" }
                        }
                    }
                    SafProviderReturn(SafProviderReturnShape.VOID)
                }
                else -> error("Unsupported SAF effect")
            }
        } catch (failure: Throwable) {
            // No cancellation poll, query or payload/hash scan occurs in this synchronous receipt finalizer.
            try { journal.returned(effect.action, SafEffectReturn.THREW, SafProviderReturn(SafProviderReturnShape.THREW,
                diagnosticClass = failure.javaClass.name.take(128), diagnosticMessage = null)) }
            catch (recordFailure: Throwable) { if (recordFailure !== failure) failure.addSuppressed(recordFailure) }
            throw failure
        }
        val completed = when (raw.shape) {
            SafProviderReturnShape.URI -> raw.uri != null
            SafProviderReturnShape.BOOLEAN -> raw.boolean == true
            else -> true
        }
        // Persist the raw return BEFORE the first post-call query, including null/false/wrong identity.
        journal.returned(effect.action, if (completed) SafEffectReturn.COMPLETED else SafEffectReturn.REJECTED, raw)
        check(completed) { "SAF provider rejected the operation; evidence retained" }
        checkActive()
        val admitted = when (kind) {
            SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> {
                val actual = requireNotNull(resolve(plan.path)) { "SAF creation is absent from its requested parent" }
                check(actual.uri == raw.uri && actual.parentDocumentId == journal.admittedParent(operation, plan).documentId &&
                    actual.name == plan.path.substringAfterLast('/') && actual.mimeType == plan.requestedMimeType) {
                    "SAF creation returned a different identity, name, type or parent; retained without correction"
                }
                actual
            }
            SafEffectKind.WRITE_FILE -> {
                val actual = requireNotNull(resolve(plan.path))
                check(actual == target(plan)) { "SAF written identity changed" }
                checkFingerprint(actual, requireNotNull(plan.intended))
                actual
            }
            SafEffectKind.DELETE -> { check(resolve(plan.path) == null) { "SAF deletion was not verified" }; null }
            else -> error("Unsupported SAF effect")
        }
        checkActive()
        journal.verified(effect.action, admitted, checkActive)
        admitted
    }

    private fun requirePlanningPhase() {
        requireCompletedLocalActions()
        check(operation.effects.values.none { it.plan.kind in PROVIDER_KINDS && it.phase >= SafEffectPhase.ATTEMPTING })
    }

    private fun requireCompletedLocalActions() {
        operation.effects.values.forEach { record ->
            checkActive()
            if (record.plan.kind !in PROVIDER_KINDS) {
                check(record.plan.kind == SafEffectKind.LOCAL_MUTATION && record.phase == SafEffectPhase.VERIFIED &&
                    record.result == SafEffectReturn.COMPLETED && record.localVerified?.forcesCompleted == true) {
                    "Provider work requires completed owned local prerequisites"
                }
                check(journal.read(operation, SafOperationJournal.actionName(record.actionId, SafEffectPhase.VERIFIED),
                    SafEffectRecord.serializer(), checkActive) == record) { "Local prerequisite receipt changed" }
            }
        }
    }

    private fun validatePlanSet() {
        // One invocation-local pass, not pairwise work after every prepare. No persistent plan cache.
        val paths = prepared.groupBy { checkActive(); it.action.admittedPlan.path }
        paths.forEach { (path, effects) ->
            checkActive()
            val replacement = effects.singleOrNull { it.action.admittedPlan.replacementDeleteActionId != null }
            val creations = if (replacement != null) {
                val create = replacement.action
                val deletion = journal.replacementDeletion(operation, create.admittedPlan)
                require(effects.first().action.id == deletion.actionId && effects.getOrNull(1) === replacement)
                require(effects.size == if (create.admittedPlan.kind == SafEffectKind.CREATE_DIRECTORY) 2 else 3) {
                    "Only an exact delete -> create -> optional write may replace a SAF path"
                }
                check(replacementCreates.put(path, create.admittedPlan) == null)
                effects.drop(1)
            } else effects
            if (creations.size != 1) {
                require(creations.size == 2) { "Duplicate SAF effects" }
                val create = creations[0].action
                val write = creations[1].action.admittedPlan
                require(create.admittedPlan.kind == SafEffectKind.CREATE_FILE && write.kind == SafEffectKind.WRITE_FILE &&
                    write.original == null && write.creationActionId == create.id && write.parent == create.admittedPlan.parent &&
                    write.parentCreationActionId == create.admittedPlan.parentCreationActionId) {
                    "Only one exact create-file -> write pair may share a SAF path"
                }
            }
            val declaredAncestors = hashSetOf<String>()
            var current = creations.first().action.admittedPlan
            while (current.parentCreationActionId != null) {
                checkActive()
                check(declaredAncestors.size < journal.limits.treeDepth)
                val id = requireNotNull(current.parentCreationActionId)
                check(declaredAncestors.add(id))
                current = operation.effects.getValue(id).plan
            }
            var slash = path.lastIndexOf('/')
            while (slash >= 0) {
                checkActive()
                paths[path.substring(0, slash)]?.let { ancestors ->
                    val directory = ancestors.singleOrNull { it.action.admittedPlan.kind == SafEffectKind.CREATE_DIRECTORY }
                    require(directory != null && directory.action.id in declaredAncestors &&
                        (ancestors.size == 1 || ancestors.size == 2 &&
                            ancestors.first().action.admittedPlan.kind == SafEffectKind.DELETE &&
                            directory.action.admittedPlan.replacementDeleteActionId == ancestors.first().action.id)) {
                        "Unsupported ancestor/descendant SAF effect plans"
                    }
                }
                slash = path.lastIndexOf('/', slash - 1)
            }
        }
    }

    private fun verifyBefore(plan: SafEffectPlan, allowPendingCreation: Boolean) {
        checkActive()
        listOfNotNull(plan.previous, plan.intended).forEach {
            journal.checkEvidence(journal.file(operation, it.name), it, checkActive)
        }
        if (!checkParentChain(plan, allowPendingCreation)) {
            check(plan.original == null && plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE))
            return // All bytes/intents exist; the declared missing parent is verified before this child's call.
        }
        when (plan.kind) {
            SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY -> {
                if (!awaitingReplacement(plan, allowPendingCreation))
                    check(resolve(plan.path) == null) { "SAF creation destination appeared" }
            }
            SafEffectKind.WRITE_FILE -> {
                val link = plan.creationActionId?.let { operation.effects.getValue(it) }
                if (link != null && link.phase != SafEffectPhase.VERIFIED) {
                    check(allowPendingCreation && link.phase == SafEffectPhase.PREPARED) { "SAF linked creation is not verified" }
                    if (!awaitingReplacement(link.plan, allowPendingCreation)) check(resolve(plan.path) == null)
                } else {
                    val expected = target(plan)
                    check(resolve(plan.path) == expected) { "SAF write target changed" }
                    if (plan.previous != null) checkFingerprint(expected, plan.previous)
                    else check(fingerprint(expected, 0).first == 0L) { "SAF new file was populated before its owned write" }
                }
            }
            SafEffectKind.DELETE -> {
                val ref = requireNotNull(plan.beforeTree)
                val bytes = journal.storage.read(journal.file(operation, ref.name), journal.limits.treeIndexBytes, checkActive)
                journal.checkEvidence(journal.file(operation, ref.name), ref, checkActive)
                val image = SafJournalCodec.decode(SafTreeEvidence.serializer(), bytes)
                check(image.operationId == operation.id && image.treeUri == tree.toString() && image.rootPath == plan.path && image.rootDocument == plan.original)
                image.entries.forEach { entry -> entry.payload?.let { journal.checkEvidence(journal.file(operation, it.name), it, checkActive) } }
                replacementCreates[plan.path]?.let { requireReplacementCapabilities(it) }
                verifyTree(image)
            }
            else -> error("Unsupported SAF effect")
        }
    }

    private fun awaitingReplacement(plan: SafEffectPlan, allowPending: Boolean): Boolean {
        if (plan.replacementDeleteActionId == null) return false
        val deletion = journal.replacementDeletion(operation, plan)
        if (deletion.phase == SafEffectPhase.PREPARED) {
            check(allowPending) { "SAF replacement deletion is not verified" }
            check(resolve(plan.path) == deletion.plan.original) { "SAF replacement original changed" }
            return true // The complete set separately verifies this DELETE's whole beforeTree before any call.
        }
        journal.admitReplacementDeletion(operation, plan, checkActive)
        return false
    }

    /** Capability observations do not become persistent identity or authorize correction/replay. */
    private fun requireReplacementCapabilities(create: SafEffectPlan) {
        val deletion = journal.replacementDeletion(operation, create)
        val original = requireNotNull(deletion.plan.original)
        val parent = requireNotNull(create.parent)
        check(resolve(create.path) == original && resolve(create.path.substringBeforeLast('/', "")) == parent)
        val oldNode = requireNotNull(calls.read(original.documentId, checkActive))
        val parentNode = requireNotNull(calls.read(parent.documentId, checkActive))
        check(document(oldNode, original.parentDocumentId) == original && document(parentNode, parent.parentDocumentId) == parent)
        check(oldNode.flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 } == true &&
            parentNode.flags?.let { it and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0 } == true) {
            "SAF replacement requires advertised delete and parent-create capabilities"
        }
    }

    private fun checkParentChain(plan: SafEffectPlan, allowPendingCreation: Boolean): Boolean {
        journal.admitParentChain(operation, plan, requireVerified = !allowPendingCreation, checkActive)
        var current = plan
        var ready = true
        var depth = 0
        while (current.parentCreationActionId != null) {
            checkActive()
            check(++depth <= journal.limits.treeDepth)
            val parent = operation.effects.getValue(requireNotNull(current.parentCreationActionId))
            if (parent.phase == SafEffectPhase.VERIFIED)
                check(resolve(parent.plan.path) == parent.returnedDocument) { "SAF created ancestor identity changed" }
            else {
                check(allowPendingCreation && parent.phase == SafEffectPhase.PREPARED) { "SAF parent creation is uncertain" }
                ready = false
            }
            current = parent.plan
        }
        check(resolve(current.path.substringBeforeLast('/', "")) == current.parent) { "SAF existing ancestor changed before effect" }
        return ready
    }

    private fun verifyTree(image: SafTreeEvidence) {
        check(resolve(image.rootPath) == image.rootDocument) { "SAF subtree root changed" }
        val expected = image.entries.associateBy { checkActive(); it.relativePath }
        val expectedChildren = image.entries.filter { checkActive(); it.relativePath.isNotEmpty() }
            .groupBy { checkActive(); it.relativePath.substringBeforeLast('/', "") }
        val seen = hashSetOf<String>()
        fun visit(relative: String, document: SafOwnedDocument) {
            checkActive()
            check(seen.add(relative) && seen.size <= journal.limits.treeEntries)
            val entry = requireNotNull(expected[relative]) { "SAF subtree gained an unpreserved descendant" }
            check(entry.document == document) { "SAF subtree identity changed" }
            if (entry.payload != null) checkFingerprint(document, entry.payload)
            else {
                val actual = children(document)
                check(actual.toSet() == expectedChildren[relative].orEmpty().map { it.document }.toSet()) {
                    "SAF subtree membership changed"
                }
                actual.forEach { visit(if (relative.isEmpty()) it.name else "$relative/${it.name}", it) }
            }
        }
        visit("", image.rootDocument)
        check(seen == expected.keys) { "SAF subtree lost an original descendant" }
    }

    private fun target(plan: SafEffectPlan): SafOwnedDocument = plan.original ?: requireNotNull(
        operation.effects.getValue(requireNotNull(plan.creationActionId)).returnedDocument)

    private fun checkFingerprint(document: SafOwnedDocument, ref: SafEvidenceFile) {
        val actual = fingerprint(document, ref.identity.bytes)
        check(actual.first == ref.identity.bytes && actual.second == ref.identity.sha256) { "SAF provider bytes changed" }
    }

    private fun fingerprint(document: SafOwnedDocument, maximum: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        requireNotNull(calls.input(Uri.parse(document.uri))) { "SAF provider input unavailable" }.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = readSafChunk(input, buffer, checkActive)
                check(n in -1..buffer.size)
                if (n < 0) break
                bytes = SafOperationJournal.boundedAdd(bytes, n.toLong(), maximum)
                digest.update(buffer, 0, n)
            }
        }
        return bytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun resolve(path: String): SafOwnedDocument? {
        SafOperationJournal.validPath(path, true)
        val root = requireNotNull(calls.read(rootId, checkActive)) { "SAF root unavailable" }
        check(root.documentId == rootId && root.isDirectory)
        var current = document(root, rootId)
        if (path.isEmpty()) return current
        for (part in path.split('/')) {
            checkActive()
            check(current.mimeType == SafOperationJournal.DIRECTORY_MIME)
            val selected = children(current).singleOrNull { it.name == part } ?: return null
            val direct = requireNotNull(calls.read(selected.documentId, checkActive)) { "SAF child disappeared" }
            check(document(direct, current.documentId) == selected) { "SAF query identity disagreement" }
            current = selected
        }
        return current
    }

    private fun children(parent: SafOwnedDocument): List<SafOwnedDocument> {
        checkActive()
        val nodes = calls.children(parent.documentId, journal.limits.treeEntries, checkActive)
        check(nodes.size <= journal.limits.treeEntries)
        val ids = hashSetOf<String>()
        val names = hashSetOf<String>()
        var characters = 0L
        return nodes.map {
            checkActive()
            check(ids.add(it.documentId) && names.add(it.name)) { "Duplicate SAF child identity/name" }
            characters = SafOperationJournal.boundedAdd(characters, it.documentId.length.toLong() + it.name.length + it.mimeType.length, 4L * 1024 * 1024)
            document(it, parent.documentId)
        }
    }

    private fun document(node: SafDocumentNode, parent: String): SafOwnedDocument {
        require(node.documentId.isNotEmpty() && node.documentId.length <= 16_384 && '\u0000' !in node.documentId)
        require(node.mimeType.length <= 255 && node.mimeType.toByteArray(Charsets.UTF_8).size <= 255 &&
            node.mimeType.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+")))
        PathSecurity.safeLeafName(node.name)
        require(node.uri == DocumentsContract.buildDocumentUriUsingTree(tree, node.documentId)) { "Foreign SAF document URI" }
        return SafOwnedDocument(node.uri.toString(), node.documentId, node.name, node.mimeType, parent)
    }

    private fun requirePath(path: String) {
        SafOperationJournal.validPath(path, false)
        require(path.count { it == '/' } + 1 <= journal.limits.treeDepth)
        require(operation.scopes.any { SafOperationJournal.contains(it, path) })
    }

    private fun <T> owned(block: () -> T): T {
        guard.requireRoot(journal.workspace)
        check(!failed) { "SAF executor is uncertain and cannot continue" }
        check(inUse.compareAndSet(false, true)) { "SAF executor does not permit parallel or reentrant calls" }
        return try { journal.ownedWrite(operation, checkActive) { block() } }
        catch (failure: Throwable) { failed = true; throw failure }
        finally { inUse.set(false) }
    }

    companion object {
        private val PROVIDER_KINDS = setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY, SafEffectKind.WRITE_FILE, SafEffectKind.DELETE)
        /** Required later admission input: an unverified creation's returned location is not owned. */
        fun blockingScopes(operation: SafOperationJournal.Operation): List<String> =
            if (operation.effects.values.any { it.plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY) &&
                it.phase >= SafEffectPhase.ATTEMPTING && it.phase != SafEffectPhase.VERIFIED }) listOf("")
            else operation.scopes

        fun android(context: Context, tree: Uri): SafProviderCalls {
            val query = SafDocumentsTreeQuery(context, tree)
            return object : SafProviderCalls {
                override fun read(id: String, checkActive: () -> Unit) = query.readDocument(id, checkActive)
                override fun children(id: String, maximum: Int, checkActive: () -> Unit) = query.listChildren(id, maximum, checkActive)
                override fun input(uri: Uri) = context.contentResolver.openInputStream(uri)
                override fun output(uri: Uri) = context.contentResolver.openOutputStream(uri, "wt")
                override fun create(parent: Uri, mime: String, name: String) = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                override fun delete(uri: Uri) = DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        }
    }
}
