package com.baystudio.droide.core

import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Directory ownership evidence only; catalog and application startup integration remain separate.
internal class ProjectDirectoryRegistration(
    private val slot: File,
    private val checkpoint: (String) -> Unit = {},
) {
    enum class State { PREPARED, PUBLICATION_PENDING, PUBLISHED_NEEDS_RECOVERY, PUBLISHED }

    data class Inspection(val state: State, val rootPath: String, val rootKey: String,
                          val nativeOutcome: AnchoredWorkspaceFiles.MoveReceipt?, val bindingId: String? = null)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable private data class Intent(
        val version: Int = 1,
        val projectId: String,
        val rootPath: String,
        val parentKey: String,
        val slotPath: String,
        val slotKey: String,
        val slotParentKey: String,
        val operation: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val bindingId: String? = null,
    )
    @Serializable private data class Prepared(val intentHash: String, val rootKey: String)
    @Serializable private data class Outcome(val preparedHash: String, val published: Boolean, val error: Int)
    @Serializable private data class Completion(val preparedHash: String, val outcomeHash: String?, val recovered: Boolean)
    private data class Record(val text: String, val key: String)
    private data class Evidence(val intent: Intent, val prepared: Prepared, val records: Map<String, Record>,
                                val inspection: Inspection)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun prepare(root: File, projectId: String, context: CoroutineContext): Inspection =
        prepareInternal(root, projectId, null, context)

    fun prepareBound(root: File, projectId: String, bindingId: String, context: CoroutineContext): Inspection {
        require(BINDING.matches(bindingId)) { "Expected an explicit registration binding fingerprint" }
        return prepareInternal(root, projectId, bindingId, context)
    }

    private fun prepareInternal(root: File, projectId: String, bindingId: String?, context: CoroutineContext): Inspection = serialized(root, projectId, context) {
        withParents(root) { parent, slots ->
            check(parent.kind(root.name) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Project destination already exists; it was not adopted" }
            check(slots.createDirectory(slot.name).durable) { "Registration location exists or creation was not confirmed; evidence was preserved" }
            slots.openDirectory(slot.name).use { owner ->
                val intent = Intent(version = if (bindingId == null) 1 else 2,
                    projectId = projectId, rootPath = root.path, parentKey = parent.directoryKey(),
                    slotPath = slot.path, slotKey = owner.directoryKey(), slotParentKey = slots.directoryKey(),
                    operation = UUID.randomUUID().toString(), bindingId = bindingId)
                checkpoint("slot-created")
                requireLocation(parent, slots, owner, intent)
                owner.forEachChild { error("Registration location is not empty; existing evidence was preserved") }
                val recordedIntent = writeRecord(owner, INTENT, json.encodeToString(intent), context)
                requireLocation(parent, slots, owner, intent)
                requireRecords(owner, mapOf(INTENT to recordedIntent))
                check(parent.kind(root.name) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Project destination changed; no existing folder was adopted" }
                check(owner.createDirectory(STAGE).durable) { "Project directory creation was not confirmed; staged data was preserved" }
                owner.openDirectory(STAGE).use { stage ->
                    val prepared = Prepared(digest(json.encodeToString(intent)), stage.directoryKey())
                    checkpoint("directory-created")
                    requireLocation(parent, slots, owner, intent)
                    requireRecords(owner, mapOf(INTENT to recordedIntent))
                    check(owner.directoryKey(STAGE) == prepared.rootKey) { "Staged directory identity changed" }
                    writeRecord(owner, PREPARED, json.encodeToString(prepared), context)
                }
                readEvidence(root, projectId, parent, slots, owner, context).inspection
            }
        }
    }

    fun inspect(root: File, projectId: String, context: CoroutineContext): Inspection =
        withEvidence(root, projectId, context) { _, _, _, evidence -> evidence.inspection }

    fun publish(root: File, projectId: String, context: CoroutineContext): Inspection =
        withEvidence(root, projectId, context) { parent, slots, owner, evidence ->
            check(evidence.inspection.state == State.PREPARED) { "Directory publication was already attempted; inspect recovery before continuing" }
            forceEvidence(owner, evidence, context)
            val publication = writeRecord(owner, PUBLICATION, evidence.records.getValue(PREPARED).text, context)
            checkpoint("before-directory-move")
            requireLocation(parent, slots, owner, evidence.intent)
            requireRecords(owner, evidence.records + (PUBLICATION to publication))
            check(owner.directoryKey(STAGE) == evidence.prepared.rootKey) { "Staged directory changed before publication" }
            context.ensureActive()
            val outcome = owner.moveNoReplaceDurable(STAGE, parent, root.name)
            checkpoint("after-directory-move")
            requireLocation(parent, slots, owner, evidence.intent)
            val result = Outcome(digest(evidence.records.getValue(PREPARED).text), outcome.published, outcome.error)
            writeRecord(owner, OUTCOME, json.encodeToString(result), context)
            check(outcome.durable) { "Directory publication was not confirmed (published=${outcome.published}, errno=${outcome.error}); all evidence was preserved" }
            val moved = readEvidence(root, projectId, parent, slots, owner, context)
            check(moved.inspection.state == State.PUBLISHED_NEEDS_RECOVERY) { "Published directory could not be verified" }
            writeCompletion(owner, moved, recovered = false, context)
            readEvidence(root, projectId, parent, slots, owner, context).inspection
        }

    fun recoverPublished(root: File, projectId: String, context: CoroutineContext): Inspection =
        withEvidence(root, projectId, context) { parent, slots, owner, evidence ->
            if (evidence.inspection.state == State.PUBLISHED) return@withEvidence evidence.inspection
            check(evidence.inspection.state == State.PUBLISHED_NEEDS_RECOVERY) {
                "No verified published directory is available; recovery did not replay creation or movement"
            }
            forceEvidence(owner, evidence, context)
            parent.openDirectory(root.name).use { published ->
                check(published.directoryKey() == evidence.prepared.rootKey) { "Published directory identity changed" }
                published.forceDirectory()
                owner.forceDirectory()
                parent.forceDirectory()
                slots.forceDirectory()
                checkpoint("recovery-directories-forced")
                requireLocation(parent, slots, owner, evidence.intent)
                requireRecords(owner, evidence.records)
                check(owner.kind(STAGE) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                    parent.directoryKey(root.name) == evidence.prepared.rootKey) { "Directory changed during recovery" }
                context.ensureActive()
                writeCompletion(owner, evidence, recovered = true, context)
            }
            readEvidence(root, projectId, parent, slots, owner, context).inspection
        }

    private fun writeCompletion(owner: AnchoredWorkspaceFiles, evidence: Evidence, recovered: Boolean, context: CoroutineContext) {
        val completion = Completion(digest(evidence.records.getValue(PREPARED).text),
            evidence.records[OUTCOME]?.text?.let(::digest), recovered)
        writeRecord(owner, COMPLETED, json.encodeToString(completion), context)
    }

    private fun readEvidence(root: File, projectId: String, parent: AnchoredWorkspaceFiles,
                             slots: AnchoredWorkspaceFiles, owner: AnchoredWorkspaceFiles,
                             context: CoroutineContext): Evidence {
        context.ensureActive()
        val names = mutableSetOf<String>()
        owner.forEachChild { name ->
            check(name in RECORDS || name == STAGE) { "Unknown or incomplete registration entry: $name; evidence was preserved" }
            names += name
        }
        check(INTENT in names && PREPARED in names) { "Directory ownership evidence is incomplete; no folder was adopted" }
        val records = names.filter { it in RECORDS }.associateWith { readRecord(owner, it) }
        val intent = decode<Intent>(records.getValue(INTENT).text)
        val supported = intent.version == 1 && intent.bindingId == null ||
            intent.version == 2 && intent.bindingId?.let(BINDING::matches) == true
        check(supported && intent.projectId == projectId && intent.rootPath == root.path &&
            intent.operation == UUID.fromString(intent.operation).toString()) { "Registration intent is foreign or unsupported" }
        requireLocation(parent, slots, owner, intent)
        val prepared = decode<Prepared>(records.getValue(PREPARED).text)
        check(prepared.intentHash == digest(records.getValue(INTENT).text) && prepared.rootKey.isNotBlank()) { "Directory ownership evidence changed" }
        val publication = records[PUBLICATION]
        check(publication == null || publication.text == records.getValue(PREPARED).text) { "Publication intent changed" }
        val outcome = records[OUTCOME]?.let { decode<Outcome>(it.text) }
        val completion = records[COMPLETED]?.let { decode<Completion>(it.text) }
        check(outcome == null || publication != null && outcome.preparedHash == digest(records.getValue(PREPARED).text) && outcome.error >= 0) {
            "Directory outcome does not match its durable intent"
        }
        check(completion == null || publication != null && completion.preparedHash == digest(records.getValue(PREPARED).text) &&
            completion.outcomeHash == records[OUTCOME]?.text?.let(::digest) &&
            (completion.recovered || outcome?.let { it.published && it.error == 0 } == true)) { "Directory completion evidence is incomplete or changed" }
        val stagePresent = owner.kind(STAGE) != AnchoredWorkspaceFiles.Kind.ABSENT
        val state = if (stagePresent) {
            check(owner.directoryKey(STAGE) == prepared.rootKey && completion == null && outcome?.published != true) {
                "Staged directory conflicts with registration evidence"
            }
            check(parent.kind(root.name) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Project destination conflicts with the retained staged directory" }
            if (publication == null) State.PREPARED else State.PUBLICATION_PENDING
        } else {
            check(publication != null && outcome?.published != false && parent.directoryKey(root.name) == prepared.rootKey) {
                "Published project directory is missing or has changed; evidence was preserved"
            }
            if (completion == null) State.PUBLISHED_NEEDS_RECOVERY else State.PUBLISHED
        }
        requireRecords(owner, records)
        requireLocation(parent, slots, owner, intent)
        context.ensureActive()
        return Evidence(intent, prepared, records, Inspection(state, root.path, prepared.rootKey,
            outcome?.let { AnchoredWorkspaceFiles.MoveReceipt(it.published, it.error) }, intent.bindingId))
    }

    private fun requireRequest(root: File, projectId: String) {
        require(PathSecurity.safeLeafName(projectId) == projectId)
        require(root.isAbsolute && root == root.canonicalFile && root.name !in listOf("", ".", ".."))
        require(slot.isAbsolute && slot == slot.canonicalFile && slot.name !in listOf("", ".", ".."))
        require(!slot.toPath().startsWith(root.toPath()) && !root.toPath().startsWith(slot.toPath())) {
            "Registration evidence and project must have separate directory trees"
        }
    }

    private inline fun <T> withParents(root: File, action: (AnchoredWorkspaceFiles, AnchoredWorkspaceFiles) -> T): T =
        AnchoredWorkspaceFiles(requireNotNull(root.parentFile)).use { parent ->
            AnchoredWorkspaceFiles(requireNotNull(slot.parentFile)).use { slots -> action(parent, slots) }
        }

    private inline fun <T> withEvidence(root: File, projectId: String, context: CoroutineContext,
        action: (AnchoredWorkspaceFiles, AnchoredWorkspaceFiles, AnchoredWorkspaceFiles, Evidence) -> T): T =
        serialized(root, projectId, context) {
            withParents(root) { parent, slots ->
                slots.openDirectory(slot.name).use { owner ->
                    action(parent, slots, owner, readEvidence(root, projectId, parent, slots, owner, context))
                }
            }
        }

    private inline fun <T> serialized(root: File, projectId: String, context: CoroutineContext, action: () -> T): T {
        requireRequest(root, projectId)
        context.ensureActive()
        val lock = registrationLock(slot.path)
        lock.lockInterruptibly()
        try {
            context.ensureActive()
            requireRequest(root, projectId)
            return action()
        } finally { lock.unlock() }
    }

    private fun requireLocation(parent: AnchoredWorkspaceFiles, slots: AnchoredWorkspaceFiles,
                                owner: AnchoredWorkspaceFiles, intent: Intent) {
        check(intent.slotPath == slot.path && parent.directoryKey() == intent.parentKey &&
            slots.directoryKey() == intent.slotParentKey && slots.directoryKey(slot.name) == intent.slotKey &&
            owner.directoryKey() == intent.slotKey) { "Registration or project parent identity changed; evidence was preserved" }
    }

    private fun forceEvidence(owner: AnchoredWorkspaceFiles, evidence: Evidence, context: CoroutineContext) {
        for ((name, record) in evidence.records) {
            context.ensureActive()
            owner.openRead(name).use { input ->
                check(input.observe().attributes.fileKey().toString() == record.key) { "Registration record identity changed" }
                input.forceContents()
            }
        }
        owner.forceDirectory()
        requireRecords(owner, evidence.records)
    }

    private fun requireRecords(owner: AnchoredWorkspaceFiles, records: Map<String, Record>) {
        records.forEach { (name, expected) -> check(readRecord(owner, name) == expected) { "Registration evidence changed: $name" } }
    }

    private fun writeRecord(owner: AnchoredWorkspaceFiles, name: String, text: String, context: CoroutineContext): Record {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_RECORD_BYTES)
        context.ensureActive()
        val temporary = ".$name.pending"
        return owner.createNew(temporary).use { output ->
            val key = output.observe().attributes.fileKey().toString()
            output.write(bytes)
            checkpoint("$name-before-force")
            context.ensureActive()
            output.force(384)
            check(readRecord(owner, temporary) == Record(text, key)) { "Registration record changed before publication" }
            checkpoint("$name-before-publication")
            context.ensureActive()
            val moved = owner.moveNoReplaceDurable(temporary, owner, name)
            checkpoint("$name-after-publication")
            check(moved.durable) { "Registration record publication was not confirmed (published=${moved.published}, errno=${moved.error}); evidence was preserved" }
            check(readRecord(owner, name) == Record(text, key)) { "Published registration record changed" }
            Record(text, key)
        }
    }

    private fun readRecord(owner: AnchoredWorkspaceFiles, name: String): Record = owner.openRead(name).use { input ->
        val before = input.observe()
        check(before.attributes.size() in 1..MAX_RECORD_BYTES.toLong()) { "Registration record is empty or oversized" }
        val bytes = ByteArray(before.attributes.size().toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0) { "Registration record was truncated" }
            offset += count
        }
        check(input.read() == -1) { "Registration record grew while reading" }
        fun same(after: AnchoredWorkspaceFiles.Observation) =
            before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed
        check(same(input.observe()) && owner.openRead(name).use { same(it.observe()) }) { "Registration record changed while reading" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        Record(text, checkNotNull(before.attributes.fileKey()).toString())
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString<T>(text).also {
        check(json.encodeToString(it) == text) { "Registration record is corrupt or unsupported" }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val STAGE = "workspace"
        const val INTENT = "intent.json"
        const val PREPARED = "prepared.json"
        const val PUBLICATION = "publication.json"
        const val OUTCOME = "outcome.json"
        const val COMPLETED = "completed.json"
        const val MAX_RECORD_BYTES = 16_384
        val BINDING = Regex("[a-f0-9]{64}")
        val RECORDS = setOf(INTENT, PREPARED, PUBLICATION, OUTCOME, COMPLETED)
        private val locks = mutableMapOf<String, WeakReference<ReentrantLock>>()

        // Cooperative callers share a lock; native no-replace remains required for external writers.
        fun registrationLock(path: String): ReentrantLock = synchronized(locks) {
            locks.entries.removeAll { it.value.get() == null }
            locks[path]?.get() ?: ReentrantLock().also { locks[path] = WeakReference(it) }
        }
    }
}
