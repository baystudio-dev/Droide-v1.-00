package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable

@Serializable internal data class SafRetirementCharge(
    val operations: Int = 0, val metadata: Long = 0, val index: Long = 0, val payload: Long = 0,
)
@Serializable internal data class SafRetirementPin(
    val id: String, val envelopeDigest: String, val planDigest: String?, val committedDigest: String?,
    val parentRevision: Long?, val parentDigest: String?,
)
@Serializable internal data class SafRetirementCustody(
    val directory: String, val key: String, val parents: List<SafLocalParent>, val nodes: List<SafLocalEvidenceEntry>,
)
@Serializable internal data class SafRetirementReceipt(
    val id: String, val directoryKey: String, val envelopeDigest: String, val committedDigest: String,
    val charge: SafRetirementCharge, val historicalPayload: Long,
    val files: List<SafEvidenceFile>, val custody: SafRetirementCustody?,
)
@Serializable internal data class SafRetirementCheckpoint(
    val version: Int, val anchorDigest: String, val generation: Long, val previousDigest: String?,
    val revision: Long, val digest: String, val highwater: Long, val visible: SafStoredFile?,
    val pins: List<SafRetirementPin>, val receipts: List<SafRetirementReceipt>,
)
@Serializable internal data class SafRetirementSectionRef(
    val ordinal: Int, val operationId: String, val kind: String, val bytes: Long, val sha256: String, val entries: Int,
)
@Serializable internal data class SafRetirementSection(
    val version: Int, val ordinal: Int, val operationId: String, val kind: String,
    val files: List<SafEvidenceFile>, val nodes: List<SafLocalEvidenceEntry>,
)
@Serializable internal data class SafRetirementFrame(
    val version: Int, val checkpoint: SafRetirementCheckpoint, val imageBytes: Long,
    val sections: List<SafRetirementSectionRef>,
)

/** A self-contained checkpoint and detached exact deletion inventories are published in ONE
 * atomic replacement. */
internal class SafJournalRetirement(private val journal: SafOperationJournal) {
    private val storage get() = journal.storage
    private val limits get() = journal.limits
    private val current get() = File(journal.directory, CURRENT)
    private val candidate get() = File(journal.directory, CANDIDATE)
    private val controlLimit get() = minOf(limits.recordBytes, 1024L * 1024)
    // SAF1 remains readable/writable for small inventories. SAF2 adds bounded sections in the SAME
    // file.
    private val wholeLimit get() = Math.addExact(limits.aggregateMetadataBytes, limits.manifestBytes)
    private class InventoryTooLarge : Exception("SAF successful inventory exceeds bounded retirement control capacity")
    private fun budget(current: Long, additional: Long): Long {
        require(current >= 0 && additional >= 0)
        if (current > controlLimit || additional > controlLimit - current) throw InventoryTooLarge()
        return current + additional
    }
    private fun retained(message: String) { journal.retirementDiagnostic = message }

    internal data class ReceiptContinuity(val versions: Map<String, SafLocalVersion?>, val children: Map<String, Set<String>>)
    internal data class State(val checkpoint: SafRetirementCheckpoint, val image: ByteArray, val identity: SafStoredFile,
        val version: SafLocalVersion,
        val fileCharge: SafRetirementCharge, val outstanding: List<SafRetirementReceipt>,
        val receiptContinuity: Map<String, ReceiptContinuity> = emptyMap()) {
        val charge: SafRetirementCharge get() = outstanding.fold(fileCharge) { total, receipt -> add(total, receipt.charge) }
    }

    // Preserve the original trailing-lambda API and its durable behavior.
    internal fun load(poll: () -> Unit): State? = load(poll, forceDurability = true)

    internal fun load(poll: () -> Unit, forceDurability: Boolean): State? {
        journal.validateRoot(poll)
        check(storage.attributes(candidate) == null) { "Incomplete SAF retirement candidate retained; admission blocked" }
        if (storage.attributes(current) == null) return null
        val (decoded, identity) = storage.readFrame(current, wholeLimit, poll) { reader ->
            val header = ByteBuffer.wrap(reader.readExact(8, 8))
            val magic = header.int
            val length = header.int.toLong()
            require(length in 1..controlLimit)
            val control = reader.readExact(length, controlLimit)
            when (magic) {
                MAGIC -> {
                    val image = reader.readExact(reader.remaining, limits.manifestBytes)
                    val head = SafJournalCodec.decode(SafRetirementCheckpoint.serializer(), control)
                    require(head.version == 1)
                    Triple(head, image, SafRetirementCharge(metadata = 8 + length, index = 8 + length, payload = image.size.toLong()))
                }
                SECTION_MAGIC -> decodeSections(control, reader, length, poll)
                else -> error("Unknown SAF checkpoint format retained")
            }
        }
        val (checkpoint, image, charge) = decoded
        validate(checkpoint, image, poll)
        // A replacement or force may have been interrupted. Only this exact durable record/link can
        // admit missing private files; an ordinary recovered COMMITTED record cannot do so.
        poll()
        if (forceDurability) {
            storage.syncFile(current)
            storage.syncDirectory(journal.directory)
        }
        journal.validateRoot(poll)
        check(storage.describe(current, wholeLimit, poll) == identity)
        val continuity = linkedMapOf<String, ReceiptContinuity>()
        val outstanding = checkpoint.receipts.filter {
            val observed = observeReceipt(it, poll)
            val absent = admitReceipt(it, poll, forceAbsent = false)
            requireReceiptContinuity(observed, poll)
            continuity[it.id] = observed
            !absent
        }
        if (forceDurability) forceAbsences(checkpoint.receipts.filter { it !in outstanding }, poll)
        check(storage.describe(current, wholeLimit, poll) == identity)
        return State(checkpoint, image, identity, requireNotNull(SafLocalFiles.version(storage, current)), charge, outstanding, continuity)
    }

    private fun control(value: SafRetirementCheckpoint, imageBytes: Int): Pair<ByteArray, SafRetirementCharge> {
        // Never serialize a large aggregate just to discover that the inline form cannot fit.
        var size = SafJournalCodec.encode(SafRetirementCheckpoint.serializer(), summaries(value)).size.toLong()
        value.receipts.forEach { receipt ->
            receipt.files.forEachIndexed { i, ref -> size = budget(size,
                SafJournalCodec.encode(SafEvidenceFile.serializer(), ref).size.toLong() + if (i == 0) 0 else 1) }
            receipt.custody?.nodes?.forEachIndexed { i, node -> size = budget(size,
                SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), node).size.toLong() + if (i == 0) 0 else 1) }
        }
        budget(0, size)
        val control = SafJournalCodec.encode(SafRetirementCheckpoint.serializer(), value)
        budget(0, control.size.toLong())
        if (8L + control.size + imageBytes > wholeLimit) throw InventoryTooLarge()
        check(imageBytes.toLong() <= limits.manifestBytes)
        return control to SafRetirementCharge(metadata = 8L + control.size, index = 8L + control.size, payload = imageBytes.toLong())
    }

    private fun summaries(head: SafRetirementCheckpoint) = head.copy(receipts = head.receipts.map {
        it.copy(files = emptyList(), custody = it.custody?.copy(nodes = emptyList()))
    })

    private data class Priced(val magic: Int, val checkpoint: SafRetirementCheckpoint,
        val control: ByteArray, val charge: SafRetirementCharge,
        val sections: List<Pair<SafRetirementSection, SafRetirementSectionRef>>)
    private data class Captured(val receipt: SafRetirementReceipt,
        val sections: List<Pair<SafRetirementSection, SafRetirementSectionRef>>, val metadataBytes: Long)

    private fun price(head: SafRetirementCheckpoint, image: ByteArray, poll: () -> Unit,
        captured: Map<String, List<Pair<SafRetirementSection, SafRetirementSectionRef>>> = emptyMap(),
        forceSections: Boolean = false, captureLimit: Long = limits.aggregateMetadataBytes): Priced {
        if (!forceSections && head.receipts.isNotEmpty()) {
            val inline = try { control(head.copy(version = 1), image.size) } catch (_: InventoryTooLarge) { null }
            if (inline != null) return Priced(MAGIC, head.copy(version = 1), inline.first, inline.second, emptyList())
        }
        val sections = arrayListOf<Pair<SafRetirementSection, SafRetirementSectionRef>>()
        var sectionBytes = 0L
        var catalogBytes = 0L
        head.receipts.forEach { receipt ->
            val existing = captured[receipt.id]
            if (existing != null) {
                existing.forEach { section ->
                    sectionBytes = inventoryBudget(sectionBytes, section.second.bytes, captureLimit)
                    catalogBytes = budget(catalogBytes,
                        SafJournalCodec.encode(SafRetirementSectionRef.serializer(), section.second).size.toLong() + 1)
                    sections += section
                }
                return@forEach
            }
            val firstOrdinal = sections.size
            fun append(kind: String, total: Int) {
                var start = 0
                while (start < total) {
                    poll()
                    val ordinal = sections.size - firstOrdinal
                    val empty = SafRetirementSection(1, ordinal, receipt.id, kind, emptyList(), emptyList())
                    var bytes = SafJournalCodec.encode(SafRetirementSection.serializer(), empty).size.toLong()
                    var end = start
                    while (end < total) {
                        poll()
                        val entry = if (kind == PRIVATE) SafJournalCodec.encode(SafEvidenceFile.serializer(), receipt.files[end])
                            else SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), requireNotNull(receipt.custody).nodes[end])
                        val additional = entry.size.toLong() + if (end == start) 0 else 1
                        if (additional > controlLimit - bytes) break
                        bytes += additional; end++
                    }
                    if (end == start) throw InventoryTooLarge()
                    val value = empty.copy(files = if (kind == PRIVATE) receipt.files.subList(start, end) else emptyList(),
                        nodes = if (kind == CUSTODY) requireNotNull(receipt.custody).nodes.subList(start, end) else emptyList())
                    val encoded = SafJournalCodec.encode(SafRetirementSection.serializer(), value)
                    check(encoded.size.toLong() == bytes && bytes <= controlLimit)
                    sectionBytes = inventoryBudget(sectionBytes, bytes, captureLimit)
                    val ref = SafRetirementSectionRef(ordinal, receipt.id, kind, bytes, encoded.safDigest(poll), end - start)
                    catalogBytes = budget(catalogBytes, SafJournalCodec.encode(SafRetirementSectionRef.serializer(), ref).size.toLong() + 1)
                    sections += value to ref
                    start = end
                }
            }
            append(PRIVATE, receipt.files.size)
            append(CUSTODY, receipt.custody?.nodes?.size ?: 0)
        }
        val frame = SafRetirementFrame(2, summaries(head.copy(version = 2)), image.size.toLong(), sections.map { it.second })
        val control = SafJournalCodec.encode(SafRetirementFrame.serializer(), frame)
        budget(0, control.size.toLong())
        val metadata = inventoryBudget(8L + control.size, sectionBytes, captureLimit)
        require(image.size.toLong() <= limits.manifestBytes)
        return Priced(SECTION_MAGIC, head.copy(version = 2), control, SafRetirementCharge(metadata = metadata,
            index = 8L + control.size, payload = image.size.toLong()), sections)
    }

    private fun write(priced: Priced, image: ByteArray, poll: () -> Unit): SafStoredFile =
        storage.writeFrame(candidate, Math.addExact(priced.charge.metadata, priced.charge.payload), poll) { append ->
            append(ByteBuffer.allocate(8).putInt(priced.magic).putInt(priced.control.size).array())
            append(priced.control)
            append(image)
            priced.sections.forEach { (value, ref) ->
                poll()
                val bytes = SafJournalCodec.encode(SafRetirementSection.serializer(), value)
                check(bytes.size.toLong() == ref.bytes && bytes.safDigest(poll) == ref.sha256)
                append(bytes)
            }
        }

    private fun decodeSections(control: ByteArray, reader: SafOperationStorage.FrameReader, length: Long,
        poll: () -> Unit): Triple<SafRetirementCheckpoint, ByteArray, SafRetirementCharge> {
        val frame = SafJournalCodec.decode(SafRetirementFrame.serializer(), control)
        require(frame.version == 2 && frame.checkpoint.version == 2 && frame.imageBytes in 0..limits.manifestBytes)
        val heads = frame.checkpoint.receipts
        require(heads.size <= 64 && heads.map { it.id }.distinct().size == heads.size)
        require(heads.all { it.files.isEmpty() && it.custody?.nodes.isNullOrEmpty() })
        val files = heads.associate { it.id to arrayListOf<SafEvidenceFile>() }
        val nodes = heads.associate { it.id to arrayListOf<SafLocalEvidenceEntry>() }
        val positions = heads.mapIndexed { index, value -> value.id to index }.toMap()
        val ordinals = heads.associate { it.id to 0 }.toMutableMap()
        var position = -1
        var metadata = 8L + length
        val image = reader.readExact(frame.imageBytes, limits.manifestBytes)
        frame.sections.forEach { ref ->
            poll()
            require(ref.ordinal == ordinals[ref.operationId] && ref.kind in setOf(PRIVATE, CUSTODY) && ref.bytes in 1..controlLimit &&
                ref.entries > 0 && isDigest(ref.sha256))
            ordinals[ref.operationId] = ref.ordinal + 1
            val owner = requireNotNull(positions[ref.operationId])
            val next = owner * 2 + if (ref.kind == PRIVATE) 0 else 1
            require(next >= position); position = next
            if (ref.kind == CUSTODY) require(heads[owner].custody != null)
            metadata = SafOperationJournal.boundedAdd(metadata, ref.bytes, limits.aggregateMetadataBytes)
            val bytes = reader.readExact(ref.bytes, controlLimit)
            require(bytes.safDigest(poll) == ref.sha256)
            val page = SafJournalCodec.decode(SafRetirementSection.serializer(), bytes)
            require(page.version == 1 && page.ordinal == ref.ordinal && page.operationId == ref.operationId && page.kind == ref.kind)
            if (ref.kind == PRIVATE) {
                require(page.nodes.isEmpty() && page.files.size == ref.entries)
                val target = files.getValue(ref.operationId)
                require(target.size.toLong() + page.files.size <= limits.actionsPerOperation.toLong() * 4 + limits.evidenceFilesPerOperation + 16L)
                page.files.forEach { entry ->
                    require(target.isEmpty() || target.last().name < entry.name)
                    target += entry
                }
            } else {
                require(page.files.isEmpty() && page.nodes.size == ref.entries)
                val target = nodes.getValue(ref.operationId)
                require(target.size.toLong() + page.nodes.size <= limits.treeEntries)
                page.nodes.forEach { entry ->
                    require(target.isEmpty() || target.last().path < entry.path)
                    target += entry
                }
            }
        }
        val head = frame.checkpoint.copy(receipts = heads.map {
            it.copy(files = files.getValue(it.id), custody = it.custody?.copy(nodes = nodes.getValue(it.id)))
        })
        return Triple(head, image, SafRetirementCharge(metadata = metadata, index = 8L + length, payload = frame.imageBytes))
    }

    private fun validate(head: SafRetirementCheckpoint, bytes: ByteArray, poll: () -> Unit) {
        require(head.version in 1..2 && head.anchorDigest == journal.anchorIdentity.sha256 && head.generation > 0)
        require(if (head.generation == 1L) head.previousDigest == null else isDigest(head.previousDigest))
        require(head.revision > 0 && head.highwater >= head.revision && isDigest(head.digest))
        require(bytes.safDigest(poll) == head.digest)
        val image = SafJournalCodec.decode(SafManifestImage.serializer(), bytes)
        require(image.version == 3 && image.revision == head.revision)
        SafDurableManifestCommit.validateEntries(image.entries, poll)
        require(head.visible != null && head.visible.bytes == bytes.size.toLong() && head.visible.sha256 == head.digest)
        require(head.pins.size <= limits.operations && head.receipts.size in (if (head.version == 1) 1 else 0)..64)
        val ids = hashSetOf<String>()
        head.pins.forEach {
            poll(); SafOperationJournal.requireUuid(it.id)
            require(ids.add(it.id) && isDigest(it.envelopeDigest))
            require(it.planDigest == null || isDigest(it.planDigest))
            require(it.committedDigest == null || (it.planDigest != null && isDigest(it.committedDigest)))
            require(if (it.planDigest == null) it.parentRevision == null && it.parentDigest == null
                else it.parentRevision != null && it.parentRevision in 0 until head.highwater && isDigest(it.parentDigest))
        }
        head.receipts.forEach {
            poll(); SafOperationJournal.requireUuid(it.id)
            require(ids.add(it.id) && it.directoryKey.isNotEmpty() && isDigest(it.envelopeDigest) && isDigest(it.committedDigest))
            require(it.charge.operations == 1 && it.charge.metadata in 1..limits.aggregateMetadataBytes &&
                it.charge.index in 1..limits.indexBytes && it.charge.payload in 0..limits.aggregatePayloadBytes)
            require(it.files.isNotEmpty() && it.files.map { ref -> ref.name }.distinct().size == it.files.size)
            require(it.files.map { ref -> ref.identity.key }.distinct().size == it.files.size)
            var privateMetadata = 0L
            var privateIndex = 0L
            var privatePayload = 0L
            it.files.forEach { ref ->
                poll(); PathSecurity.safeLeafName(ref.name)
                require(ref.identity.key.isNotEmpty() && ref.identity.bytes >= 0 && isDigest(ref.identity.sha256))
                if (ref.name.endsWith(".bin")) privatePayload = SafOperationJournal.boundedAdd(privatePayload, ref.identity.bytes, it.charge.payload)
                else privateMetadata = SafOperationJournal.boundedAdd(privateMetadata, ref.identity.bytes, it.charge.metadata)
                if (ref.name == "operation.json" || ref.name in SafOperationJournal.manifestNames || ref.name == "abandoned.json")
                    privateIndex = SafOperationJournal.boundedAdd(privateIndex, ref.identity.bytes, it.charge.index)
            }
            require(privateMetadata == it.charge.metadata && privateIndex == it.charge.index)
            require(it.historicalPayload >= 0)

            privatePayload = SafOperationJournal.boundedAdd(privatePayload,
                it.files.single { ref -> ref.name == "planned.bin" }.identity.bytes, it.charge.payload)
            privatePayload = SafOperationJournal.boundedAdd(privatePayload, it.historicalPayload, it.charge.payload)
            require(it.files.single { ref -> ref.name == "operation.json" }.identity.sha256 == it.envelopeDigest)
            require(it.files.single { ref -> ref.name == "committed.json" }.identity.sha256 == it.committedDigest)
            it.custody?.let { custody ->
                require(custody.directory == ".droide/saf-delete-custody/${it.id}" && custody.key.isNotEmpty())
                require(custody.parents.map { parent -> parent.path } == listOf("", ".droide", ".droide/saf-delete-custody"))
                require(custody.parents.all { parent -> parent.key.isNotEmpty() })
                require(custody.nodes.isNotEmpty() && custody.nodes.size <= limits.treeEntries)
                val seen = hashMapOf<String, SafLocalKind>()
                val keys = hashSetOf<String>()
                var previous: String? = null
                var files = 0
                var bytes = 0L
                custody.nodes.forEach { node ->
                    poll(); require(node.path == "original" || node.path.startsWith("original/"))
                    node.path.split('/').forEach { part -> PathSecurity.safeLeafName(part) }
                    require(node.path.count { c -> c == '/' } < limits.treeDepth)
                    require(node.path !in seen)
                    require(previous == null || requireNotNull(previous) < node.path)
                    previous = node.path
                    require(node.path == "original" || seen[node.path.substringBeforeLast('/')] == SafLocalKind.DIRECTORY)
                    seen[node.path] = node.version.kind
                    SafLocalEvidenceFormat.validateVersion(node.version)
                    require(keys.add(node.version.key)) { "Aliased SAF retirement custody retained" }
                    if (node.version.kind == SafLocalKind.FILE) {
                        check(++files <= limits.treeFiles)
                        bytes = SafOperationJournal.boundedAdd(bytes, node.version.bytes, limits.treeBytes)
                    }
                    require(when (node.version.kind) {
                        SafLocalKind.FILE -> isDigest(node.sha256) && node.linkTarget == null
                        SafLocalKind.DIRECTORY -> node.sha256 == null && node.linkTarget == null
                        SafLocalKind.SYMLINK -> node.sha256 == null && node.linkTarget != null &&
                            node.linkTarget.toByteArray().size <= 32 * 1024 && '\u0000' !in node.linkTarget
                    })
                }
                privatePayload = SafOperationJournal.boundedAdd(privatePayload, bytes, it.charge.payload)
            }
            require(privatePayload == it.charge.payload)
        }
    }

    internal fun admitOperation(state: State?, operation: SafOperationJournal.Operation, poll: () -> Unit) {
        if (state == null) { require(operation.envelope.headDigest == null); return }
        val pin = state.checkpoint.pins.singleOrNull { it.id == operation.id }
        if (pin == null) {
            check(operation.envelope.headDigest == state.identity.sha256) { "Unpinned historical SAF operation retained" }
            return
        }
        check(pin.envelopeDigest == operation.envelopeIdentity.sha256)
        pin.planDigest?.let { check(digest(operation, "manifest-plan.json", poll) == it) }
        pin.committedDigest?.let { check(digest(operation, "committed.json", poll) == it) }
    }

    /** Re-admit mutable receipt namespaces without rereading the already admitted frame. */
    internal fun readmitReceipts(state: State?, poll: () -> Unit): State? {
        if (state == null) return null
        val continuity = linkedMapOf<String, ReceiptContinuity>()
        val outstanding = state.checkpoint.receipts.filter {
            val observed = observeReceipt(it, poll)
            val absent = admitReceipt(it, poll, forceAbsent = false)
            requireReceiptContinuity(observed, poll)
            continuity[it.id] = observed
            !absent
        }
        forceAbsences(state.checkpoint.receipts.filter { it !in outstanding }, poll)
        return state.copy(outstanding = outstanding, receiptContinuity = continuity)
    }

    /** Direct receipt namespace observations surround its single deep admission, never replace it. */
    private fun observeReceipt(receipt: SafRetirementReceipt, poll: () -> Unit): ReceiptContinuity {
        val versions = linkedMapOf<String, SafLocalVersion?>()
        val ownedDirectories = linkedSetOf<String>()
        fun record(file: File, version: SafLocalVersion?, owned: Boolean) {
            val path = file.absolutePath
            versions[path] = version
            if (owned && version?.kind == SafLocalKind.DIRECTORY) ownedDirectories += path
        }
        val root = File(journal.directory, receipt.id)
        fun privateRoot(): SafLocalVersion? {
            check(storage.key(journal.directory, true) == journal.anchor.journalKey)
            return SafLocalFiles.version(storage, root).also {
                check(it == null || it.kind == SafLocalKind.DIRECTORY && it.key == receipt.directoryKey) { "SAF receipt root changed" }
            }
        }
        poll()
        val privateVersion = privateRoot()
        record(root, privateVersion, true)
        receipt.files.forEach { ref ->
            poll()
            check(privateRoot() == privateVersion)
            val file = File(root, ref.name)
            val version = if (privateVersion == null) null else SafLocalFiles.version(storage, file)
            check(version == null || version.kind == SafLocalKind.FILE && version.key == ref.identity.key)
            record(file, version, true)
        }
        receipt.custody?.let { custody ->
            val owned = File(journal.workspace, custody.directory)
            // The validated parent list is root-first. No callback may interleave these direct type/key
            // checks with descendant stats: NOFOLLOW protects only the final component.
            fun parents() {
                custody.parents.forEach { parent ->
                    val file = File(journal.workspace, parent.path)
                    val version = SafLocalFiles.version(storage, file)
                    check(version?.kind == SafLocalKind.DIRECTORY && version.key == parent.key) { "SAF receipt custody parent changed" }
                }
            }
            fun custodyRoot(): SafLocalVersion? {
                parents()
                return SafLocalFiles.version(storage, owned).also {
                    check(it == null || it.kind == SafLocalKind.DIRECTORY && it.key == custody.key) { "SAF receipt custody root changed" }
                }
            }
            poll()
            val rootVersion = custodyRoot()
            custody.parents.forEach { record(File(journal.workspace, it.path),
                SafLocalFiles.version(storage, File(journal.workspace, it.path)), false) }
            record(owned, rootVersion, true)
            val nodes = custody.nodes.associateBy { it.path }
            custody.nodes.forEach { node ->
                poll()
                check(custodyRoot() == rootVersion)
                var reachable = rootVersion != null
                var prefix = ""
                node.path.split('/').dropLast(1).forEach { part ->
                    prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                    if (reachable) {
                        val parent = File(owned, prefix)
                        val observed = SafLocalFiles.version(storage, parent)
                        val expected = nodes.getValue(prefix).version
                        check(observed == null || observed.kind == SafLocalKind.DIRECTORY && observed.key == expected.key)
                        check(observed == versions.getValue(parent.absolutePath))
                        reachable = observed != null
                    }
                }
                val file = File(owned, node.path)
                val observed = if (reachable) SafLocalFiles.version(storage, file) else null
                check(observed == null || observed.kind == node.version.kind && observed.key == node.version.key)
                record(file, observed, true)
            }
        }
        val children = ownedDirectories.associateWith { linkedSetOf<String>() }
        versions.forEach { (path, version) -> if (version != null) {
            val file = File(path)
            children[file.parent]?.add(file.name)
        } }
        return ReceiptContinuity(versions, children)
    }

    internal fun requireReceiptContinuity(state: State?) {
        if (state == null) return
        check(state.receiptContinuity.keys == state.checkpoint.receipts.map { it.id }.toSet())
        state.receiptContinuity.values.forEach { requireReceiptContinuity(it, journal::ensureVerificationActive) }
    }

    private fun requireReceiptContinuity(observed: ReceiptContinuity, poll: () -> Unit) {
        fun parents(file: File): Boolean {
            check(storage.key(journal.workspace, true) == journal.anchor.workspaceKey)
            check(storage.key(journal.directory, true) == journal.anchor.journalKey)
            val ancestors = generateSequence(file.parentFile) { it.parentFile }
                .filter { observed.versions.containsKey(it.absolutePath) }.toList().asReversed()
            for (parent in ancestors) {
                val expected = observed.versions[parent.absolutePath]
                val actual = SafLocalFiles.version(storage, parent)
                if (expected == null) { check(actual == null); return false }
                check(expected.kind == SafLocalKind.DIRECTORY && actual?.kind == SafLocalKind.DIRECTORY && actual.key == expected.key)
            }
            return true
        }
        observed.versions.forEach { (path, version) ->
            poll()
            val file = File(path)
            val actual = if (parents(file)) SafLocalFiles.version(storage, file) else null
            check(actual == version) { "SAF receipt namespace changed during admission" }
        }
        observed.children.forEach { (path, expected) ->
            poll()
            val directory = File(path)
            check(parents(directory) && SafLocalFiles.version(storage, directory) == observed.versions[path])
            val seen = hashSetOf<String>()
            Files.newDirectoryStream(directory.toPath()).use { stream -> for (child in stream) {
                poll()
                check(parents(directory) && SafLocalFiles.version(storage, directory) == observed.versions[path])
                val name = child.fileName.toString()
                check(name in expected && seen.size < expected.size && seen.add(name)) { "Unknown SAF receipt child retained" }
            } }
            check(seen == expected)
        }
    }

    internal fun beforeBegin(existing: SafOperationJournal.Admission, incoming: SafJournalReservation,
        poll: () -> Unit): SafOperationJournal.Admission {
        journal.retirementDiagnostic = null
        var admission = existing
        var all = admission.operations
        var old = admission.head
        if (old != null && old.outstanding.isNotEmpty()) {
            cleanup(old, poll)
            admission = compact(journal.recoverAdmission(poll), poll)
            all = admission.operations
            old = admission.head
        } else if (old?.checkpoint?.version == 2 && old.checkpoint.receipts.isNotEmpty()) {
            admission = compact(admission, poll)
            all = admission.operations
            old = admission.head
        }
        val highwater = minOf(64, maxOf(2, limits.operations / 2))
        val tail = minOf(8, maxOf(1, highwater / 4))
        val committed = all.filter { "committed.json" in it.files }.sortedBy {
            journal.read(it, "manifest-plan.json", SafManifestPlan.serializer(), poll).revision
        }
        val pressure = !journal.capacityFits(all, old?.charge ?: SafRetirementCharge(), incoming)
        if (!pressure && committed.size < highwater) return admission
        val count = minOf(64, maxOf(0, committed.size - if (pressure) 0 else tail))
        if (count == 0) return admission
        val publisher = SafDurableManifestCommit(journal)
        val trusted = publisher.trusted(admission, poll)
        val visible = publisher.admitBeforeEffects(admission, poll)
        // Retirement never promotes a recognizable pending candidate over the acknowledged image.
        if (visible == null || visible.sha256 != trusted.digest || visible.bytes != trusted.bytes.size.toLong()) return admission
        val selected = arrayListOf<SafRetirementReceipt>()
        val captured = linkedMapOf<String, List<Pair<SafRetirementSection, SafRetirementSectionRef>>>()
        var capturedMetadata = 0L
        for (operation in committed.dropLast(if (pressure) 0 else tail)) {
            if (selected.size == count) break
            poll()
            val capturedReceipt = try { inventory(operation, limits.aggregateMetadataBytes - capturedMetadata, poll) } catch (_: InventoryTooLarge) {
                retained("Oversize successful operation ${operation.id} remains fully charged under the bounded capture budget")
                null
            } ?: continue
            selected += capturedReceipt.receipt
            captured[capturedReceipt.receipt.id] = capturedReceipt.sections
            capturedMetadata = inventoryBudget(capturedMetadata, capturedReceipt.metadataBytes)
        }
        if (selected.isEmpty()) return admission
        // All immutable pins are read once. Shrinking the finite batch below only re-encodes bounded
        // control, never rereads historical bodies or copies the manifest repeatedly.
        val allPinned = try { proposal(old, all, emptyList(), trusted, visible, poll) } catch (_: InventoryTooLarge) {
            retained("Retained operation pins exceed bounded retirement control capacity")
            return admission
        }
        var chosen: Priced? = null
        while (selected.isNotEmpty()) {
            poll()
            val ids = selected.map { it.id }.toSet()
            val proposal = allPinned.copy(pins = allPinned.pins.filter { it.id !in ids }, receipts = selected.toList())
            val priced = try { price(proposal, trusted.bytes, poll, captured) } catch (_: InventoryTooLarge) { null }
            if (priced != null && journal.capacityFits(all, add(old?.charge ?: SafRetirementCharge(), priced.charge))) {
                chosen = priced
                break
            }
            selected.removeAt(selected.lastIndex)
        }
        if (chosen == null) {
            retained("SAF retirement transition lacks exact bounded control/headroom; successful history retained")
            return admission
        }
        // The direct freshly published State must satisfy the same complete typed invariants as cold
        // decode, including private/custody identity uniqueness, before any allocation.
        validate(chosen.checkpoint, trusted.bytes, poll)

        val fresh = journal.recoverAdmission(poll)
        check(fresh.head?.identity == old?.identity)
        check(fresh.operations.map { it.id }.toSet() == all.map { it.id }.toSet())
        requirePins(chosen.checkpoint, fresh.operations, poll)
        selected.forEach { requireComplete(it, poll) }
        journal.validateRoot(poll)
        val created = write(chosen, trusted.bytes, poll)
        poll()
        val cancel = journal::ensureVerificationActive
        val selectedContinuity = selected.map { receipt ->
            val observed = observeReceipt(receipt, cancel)
            requireComplete(receipt, cancel)
            requireReceiptContinuity(observed, cancel)
            observed
        }
        requirePins(chosen.checkpoint, fresh.operations, cancel)
        val visibleContinuity = publisher.requireAdmittedVisible(visible, cancel)
        reseal(old, created)
        journal.requireNamespaceUnchanged(fresh, created)
        selectedContinuity.forEach { requireReceiptContinuity(it, cancel) }
        publisher.requireVisibleContinuity(visibleContinuity)
        storage.replace(candidate, current)
        storage.syncFile(current)
        storage.syncDirectory(journal.directory)
        journal.validateRoot(cancel)
        check(storage.describe(current, wholeLimit, cancel) == created)
        // This is a NEW verified state from the exact newly forced bytes, not the old admission.
        val published = State(chosen.checkpoint, trusted.bytes, created,
            requireNotNull(SafLocalFiles.version(storage, current)), chosen.charge, selected.toList())
        cleanup(published, poll)
        return compact(journal.recoverAdmission(poll), poll)
    }

    /** Before co-publication, optional plan/commit absence is as exact as their digest pins. */
    private fun requirePins(head: SafRetirementCheckpoint, all: List<SafOperationJournal.Operation>, poll: () -> Unit) {
        val operations = all.associateBy { it.id }
        head.pins.forEach { pin ->
            poll()
            val operation = operations.getValue(pin.id)
            check(digest(operation, "operation.json", poll) == pin.envelopeDigest)
            for ((name, expected) in listOf("manifest-plan.json" to pin.planDigest, "committed.json" to pin.committedDigest)) {
                if (expected == null) check(storage.attributes(journal.file(operation, name)) == null)
                else check(digest(operation, name, poll) == expected)
            }
        }
    }

    /** Completed sections leave only through another exactly priced, durable atomic successor. */
    private fun compact(admission: SafOperationJournal.Admission, poll: () -> Unit): SafOperationJournal.Admission {
        val old = admission.head ?: return admission
        if (old.checkpoint.version != 2 || old.outstanding.isNotEmpty() || old.checkpoint.receipts.isEmpty()) return admission
        val publisher = SafDurableManifestCommit(journal)
        val trusted = publisher.trusted(admission, poll)
        // Keep the checkpoint's trusted bytes exactly; later acknowledged descendants remain pinned.
        val checkpointTrusted = SafDurableManifestCommit.Trusted(old.image,
            SafJournalCodec.decode(SafManifestImage.serializer(), old.image).entries, old.checkpoint.revision, old.checkpoint.digest)
        check(trusted.revision >= checkpointTrusted.revision)
        val next = try { proposal(old, admission.operations, emptyList(), checkpointTrusted,
            requireNotNull(old.checkpoint.visible), poll).copy(version = 2) } catch (_: InventoryTooLarge) {
            retained("SAF compact checkpoint pins exceed bounded control; completed sections retained")
            return admission
        }
        val priced = try { price(next, old.image, poll) } catch (_: InventoryTooLarge) {
            retained("SAF compact checkpoint control exceeds its bound; completed sections retained")
            return admission
        }
        if (!journal.capacityFits(admission.operations, add(old.charge, priced.charge))) {
            retained("SAF compact checkpoint lacks exact headroom; completed sections remain charged")
            return admission
        }

        forceAbsences(old.checkpoint.receipts, poll)
        requireCheckpoint(old, poll)
        requirePins(next, admission.operations, poll)
        val created = write(priced, old.image, poll)
        forceAbsences(old.checkpoint.receipts, poll)
        requirePins(next, admission.operations, poll)
        poll()
        reseal(old, created)
        journal.requireNamespaceUnchanged(admission, created)
        storage.replace(candidate, current)
        storage.syncFile(current)
        storage.syncDirectory(journal.directory)
        check(storage.describe(current, wholeLimit, journal::ensureVerificationActive) == created)
        // A new digest binds all survivors through the fresh exact pins. Never reuse old state.
        return journal.recoverAdmission(poll)
    }

    private fun proposal(old: State?, all: List<SafOperationJournal.Operation>, receipts: List<SafRetirementReceipt>,
        trusted: SafDurableManifestCommit.Trusted, visible: SafStoredFile, poll: () -> Unit): SafRetirementCheckpoint {
        check(old?.outstanding.isNullOrEmpty()) { "Unfinished SAF retirement receipts cannot be dropped" }
        val retired = receipts.map { it.id }.toSet()
        var highwater = old?.checkpoint?.highwater ?: 0L
        var encodedPins = 0L
        val pins = all.filter { it.id !in retired }.map { operation ->
            poll()
            val plan = if ("manifest-plan.json" in operation.files)
                journal.read(operation, "manifest-plan.json", SafManifestPlan.serializer(), poll) else null
            if (plan != null) highwater = maxOf(highwater, plan.revision)
            SafRetirementPin(operation.id, operation.envelopeIdentity.sha256,
                plan?.let { digest(operation, "manifest-plan.json", poll) },
                if ("committed.json" in operation.files) digest(operation, "committed.json", poll) else null,
                plan?.parentRevision, plan?.parentDigest).also {
                encodedPins = budget(encodedPins, SafJournalCodec.encode(SafRetirementPin.serializer(), it).size.toLong())
            }
        }.sortedBy { it.id }
        highwater = maxOf(highwater, trusted.revision)
        check((old?.checkpoint?.generation ?: 0L) < Long.MAX_VALUE)
        return SafRetirementCheckpoint(1, journal.anchorIdentity.sha256, (old?.checkpoint?.generation ?: 0L) + 1,
            old?.identity?.sha256, trusted.revision, trusted.digest, highwater, visible, pins, receipts.toList())
    }

    private fun inventory(operation: SafOperationJournal.Operation, captureLimit: Long, poll: () -> Unit): Captured? {
        check("committed.json" in operation.files)

        for (effect in operation.effects.values) {
            poll()
            if (effect.plan.localArtifacts.any { it.kind != SafLocalArtifactKind.LOCAL_ROOT &&
                    storage.attributes(File(it.path)) != null }) return null
            val local = effect.plan.local ?: continue
            if (local.effect == SafLocalEffect.WRITE && local.source != null &&
                storage.attributes(SafLocalFiles.resolve(journal, local.source)) != null) return null
            if (local.effect == SafLocalEffect.DELETE_STAGE && local.deleteCustody == null) return null
        }
        if (storage.attributes(File(journal.workspace, ".droide/.saf-manifest-${operation.id}.candidate")) != null) return null
        val deletes = operation.effects.values.mapNotNull { it.plan.local }.filter { it.effect == SafLocalEffect.DELETE_STAGE }
        if (deletes.size > 1) return null
        var encoded = 0L
        val custody = deletes.singleOrNull()?.let { local ->
            val binding = requireNotNull(local.deleteCustody)
            val image = journal.localInventory(operation, local, poll)
            SafLocalFiles.verify(journal, operation, image, local.destination, binding, poll)
            val parents = local.parents.filter { it.path in setOf("", ".droide", ".droide/saf-delete-custody") }
                .sortedBy { it.path.length }
            require(parents.map { it.path } == listOf("", ".droide", ".droide/saf-delete-custody"))
            SafLocalFiles.requireParents(journal, parents, poll)
            check(storage.key(File(journal.workspace, binding.directory), true) == binding.directoryKey)
            SafRetirementCustody(binding.directory, binding.directoryKey, parents, image.entries.map {
                it.copy(path = "original" + if (it.path.isEmpty()) "" else "/${it.path}").also { node ->
                    encoded = inventoryBudget(encoded, SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), node).size.toLong() + 1, captureLimit)
                }
            })
        }
        val files = operation.files.sorted().map { name ->
            poll()
            SafEvidenceFile(name, storage.describe(journal.file(operation, name),
                maxOf(limits.manifestBytes, limits.treeIndexBytes, limits.recordBytes, operation.envelope.reservation.payloadBytes), poll)).also {
                encoded = inventoryBudget(encoded, SafJournalCodec.encode(SafEvidenceFile.serializer(), it).size.toLong() + 1, captureLimit)
            }
        }
        val privatePayload = files.filter { it.name.endsWith(".bin") }.sumOf { it.identity.bytes }
        val custodyPayload = custody?.nodes?.filter { it.version.kind == SafLocalKind.FILE }?.sumOf { it.version.bytes } ?: 0L
        val historical = operation.payloadBytes - privatePayload - operation.candidatePayloadBytes - custodyPayload
        check(historical >= 0)
        val receipt = SafRetirementReceipt(operation.id, operation.envelope.directoryKey, operation.envelopeIdentity.sha256,
            digest(operation, "committed.json", poll), SafRetirementCharge(1, operation.metadataBytes, operation.indexBytes, operation.payloadBytes),
            historical, files, custody)
        // Price every page once per capture. A shrinking batch only rebuilds bounded head/catalog.
        val priced = price(SafRetirementCheckpoint(2, journal.anchorIdentity.sha256, 1, null, 1, "0".repeat(64), 1,
            null, emptyList(), listOf(receipt)), ByteArray(0), poll, forceSections = true, captureLimit = captureLimit)
        requireComplete(receipt, poll)
        return Captured(receipt, priced.sections, priced.charge.metadata)
    }

    private fun inventoryBudget(current: Long, additional: Long, maximum: Long = limits.aggregateMetadataBytes): Long {
        require(current >= 0 && additional >= 0 && maximum in 0..limits.aggregateMetadataBytes)
        if (additional > maximum - current) throw InventoryTooLarge()
        return current + additional
    }

    /** Before publication, even one missing node is uncertainty, never retirement progress. */
    private fun requireComplete(receipt: SafRetirementReceipt, poll: () -> Unit) {
        check(!admitReceipt(receipt, poll))
        val root = File(journal.directory, receipt.id)
        check(storage.key(root, true) == receipt.directoryKey)
        receipt.files.forEach { poll(); check(storage.describe(File(root, it.name), it.identity.bytes, poll) == it.identity) }
        receipt.custody?.let { custody ->
            val owned = File(journal.workspace, custody.directory)
            check(storage.key(owned, true) == custody.key)
            custody.nodes.forEach { poll(); verifyNode(File(owned, it.path), it, poll) }
        }
    }

    /** True only after BOTH namespaces are absent and their exact parent links have been forced. */
    private fun admitReceipt(receipt: SafRetirementReceipt, poll: () -> Unit, forceAbsent: Boolean = true): Boolean {
        journal.validateRoot(poll)
        val root = File(journal.directory, receipt.id)
        val privatePresent = storage.attributes(root) != null
        if (privatePresent) {
            check(storage.key(root, true) == receipt.directoryKey)
            val expected = receipt.files.associateBy { it.name }
            Files.newDirectoryStream(root.toPath()).use { stream -> stream.forEach {
                poll()
                val ref = requireNotNull(expected[it.fileName.toString()]) { "Unknown SAF retirement file retained" }
                check(storage.describe(it.toFile(), ref.identity.bytes, poll) == ref.identity)
            } }
            check(storage.key(root, true) == receipt.directoryKey)
        }
        val custodyPresent = receipt.custody?.let { admitCustody(it, poll) } ?: false
        if (privatePresent || custodyPresent) return false
        if (forceAbsent) forceAbsences(listOf(receipt), poll)
        return true
    }

    private fun forceAbsences(receipts: List<SafRetirementReceipt>, poll: () -> Unit) {
        if (receipts.isEmpty()) return
        fun absent() {
            journal.validateRoot(poll)
            receipts.forEach { receipt ->
                poll(); check(storage.attributes(File(journal.directory, receipt.id)) == null)
                receipt.custody?.let { custody ->
                    SafLocalFiles.requireParents(journal, custody.parents, poll)
                    check(storage.attributes(File(journal.workspace, custody.directory)) == null)
                }
            }
        }
        absent()
        poll(); storage.syncDirectory(journal.directory)
        if (receipts.any { it.custody != null }) storage.syncDirectory(File(journal.workspace, ".droide/saf-delete-custody"))
        absent()
    }

    private fun admitCustody(custody: SafRetirementCustody, poll: () -> Unit): Boolean {
        SafLocalFiles.requireParents(journal, custody.parents, poll)
        val root = File(journal.workspace, custody.directory)
        if (storage.attributes(root) == null) return false
        check(storage.key(root, true) == custody.key)
        val expected = custody.nodes.associateBy { it.path }
        fun visit(directory: File, prefix: String) {
            Files.newDirectoryStream(directory.toPath()).use { stream -> stream.forEach { path ->
                poll()
                val relative = prefix + path.fileName.toString()
                val node = requireNotNull(expected[relative]) { "Unknown SAF custody node retained" }
                verifyNode(path.toFile(), node, poll)
                if (node.version.kind == SafLocalKind.DIRECTORY) visit(path.toFile(), "$relative/")
            } }
        }
        visit(root, "")
        check(storage.key(root, true) == custody.key)
        SafLocalFiles.requireParents(journal, custody.parents, poll)
        return true
    }

    private fun verifyNode(file: File, node: SafLocalEvidenceEntry, poll: () -> Unit) {
        val actual = requireNotNull(SafLocalFiles.version(storage, file))
        check(actual.kind == node.version.kind && actual.key == node.version.key)
        when (node.version.kind) {
            SafLocalKind.DIRECTORY -> Unit // Child unlink legitimately changes size/mtime, never key.
            SafLocalKind.FILE -> check(storage.describe(file, node.version.bytes, poll) ==
                SafStoredFile(node.version.key, node.version.bytes, requireNotNull(node.sha256)))
            SafLocalKind.SYMLINK -> check(Files.readSymbolicLink(file.toPath()).toString() == node.linkTarget)
        }
    }

    private fun cleanup(state: State, poll: () -> Unit) {
        val authority = requireCheckpoint(state, poll)
        val cancel = journal::ensureVerificationActive
        for (receipt in state.outstanding) {
            poll()
            requireCheckpointLink(state, poll)
            if (admitReceipt(receipt, poll)) continue
            receipt.custody?.let { custody ->
                val root = File(journal.workspace, custody.directory)
                val nodes = custody.nodes.associateBy { it.path }
                if (storage.attributes(root) != null) {
                    custody.nodes.sortedByDescending { it.path.count { c -> c == '/' } }.forEach { node ->
                        poll()
                        val file = File(root, node.path)
                        if (storage.attributes(file) != null) {
                            requireCheckpointLink(state, poll)
                            requireCustodyParents(custody, nodes, node.path, poll)
                            verifyNode(file, node, poll)
                            requireCleanupCut(state, authority)
                            requireCustodyParents(custody, nodes, node.path, cancel)
                            storage.removeExact(file, node.version.key, node.version.kind)
                        }
                    }
                    requireCheckpointLink(state, poll)
                    SafLocalFiles.requireParents(journal, custody.parents, poll)
                    requireCleanupCut(state, authority)
                    SafLocalFiles.requireParents(journal, custody.parents, cancel)
                    storage.removeExact(root, custody.key, SafLocalKind.DIRECTORY)
                }
            }
            val root = File(journal.directory, receipt.id)
            if (storage.attributes(root) != null) {
                // Payload first; all private metadata follows; the ownership envelope is last.
                receipt.files.sortedWith(compareBy<SafEvidenceFile> {
                    if (it.name.endsWith(".bin")) 0 else if (it.name == "operation.json") 2 else 1
                }.thenBy { it.name }).forEach { ref ->
                    poll()
                    val file = File(root, ref.name)
                    if (storage.attributes(file) != null) {
                        requireCheckpointLink(state, poll)
                        check(storage.key(root, true) == receipt.directoryKey)
                        check(storage.describe(file, ref.identity.bytes, poll) == ref.identity)
                        requireCleanupCut(state, authority)
                        check(storage.key(root, true) == receipt.directoryKey)
                        storage.removeExact(file, ref.identity.key, SafLocalKind.FILE)
                    }
                }
                requireCheckpointLink(state, poll)
                requireCleanupCut(state, authority)
                storage.removeExact(root, receipt.directoryKey, SafLocalKind.DIRECTORY)
            }
            check(admitReceipt(receipt, poll))
        }
        requireCheckpoint(state, poll)
    }

    private fun requireCustodyParents(custody: SafRetirementCustody, nodes: Map<String, SafLocalEvidenceEntry>,
        path: String, poll: () -> Unit) {
        poll()
        SafLocalFiles.requireParents(journal, custody.parents, journal::ensureVerificationActive)
        val root = File(journal.workspace, custody.directory)
        check(storage.key(root, true) == custody.key)
        var parent = ""
        for (part in path.split('/').dropLast(1)) {
            parent = if (parent.isEmpty()) part else "$parent/$part"
            journal.ensureVerificationActive()
            val expected = requireNotNull(nodes[parent])
            check(expected.version.kind == SafLocalKind.DIRECTORY && storage.key(File(root, parent), true) == expected.version.key)
        }
    }

    private fun requireCheckpointLink(state: State, poll: () -> Unit) {
        journal.validateRoot(poll)
        // Content was fully admitted/forced once. This continuity check does not establish trust from
        // timestamps: it also pins the exact inode, and no callback follows node validation.
        check(SafLocalFiles.version(storage, current) == state.version)
        check(storage.attributes(candidate) == null)
    }

    private data class CleanupAuthority(val anchor: SafLocalVersion, val baseline: SafLocalVersion)

    private fun requireCleanupCut(state: State, authority: CleanupAuthority) {
        journal.ensureVerificationActive()
        check(storage.key(journal.workspace, true) == journal.anchor.workspaceKey)
        check(storage.key(journal.directory, true) == journal.anchor.journalKey)
        check(SafLocalFiles.version(storage, File(journal.directory, "anchor.json")) == authority.anchor)
        check(SafLocalFiles.version(storage, File(journal.directory, "baseline.bin")) == authority.baseline)
        check(SafLocalFiles.version(storage, current) == state.version)
        check(storage.attributes(candidate) == null)
    }

    private fun requireCheckpoint(state: State, poll: () -> Unit): CleanupAuthority {
        journal.validateRoot(poll)
        val authority = CleanupAuthority(requireNotNull(SafLocalFiles.version(storage, File(journal.directory, "anchor.json"))),
            requireNotNull(SafLocalFiles.version(storage, File(journal.directory, "baseline.bin"))))
        check(storage.describe(current, wholeLimit, poll) == state.identity)
        check(storage.attributes(candidate) == null)
        requireCleanupCut(state, authority)
        return authority
    }

    /** Final authority cut. The cancellation-only implementation is wired separately from caller polls. */
    internal fun reseal(state: State?, expectedCandidate: SafStoredFile? = null) {
        val cancel = journal::ensureVerificationActive
        val candidateVersion = if (expectedCandidate == null) {
            check(storage.attributes(candidate) == null); null
        } else {
            check(storage.describe(candidate, wholeLimit, cancel) == expectedCandidate)
            requireNotNull(SafLocalFiles.version(storage, candidate))
        }
        journal.validateRoot(cancel)
        val anchor = File(journal.directory, "anchor.json")
        val anchorVersion = requireNotNull(SafLocalFiles.version(storage, anchor))
        val baseline = File(journal.directory, "baseline.bin")
        journal.checkEvidence(baseline, journal.anchor.baseline, cancel)
        val baselineVersion = requireNotNull(SafLocalFiles.version(storage, baseline))
        if (state == null) check(storage.attributes(current) == null)
        else check(storage.describe(current, wholeLimit, cancel) == state.identity)

        cancel()
        storage.requireRealAncestors(journal.workspace)
        storage.requireRealAncestors(journal.directory)
        check(storage.key(journal.workspace, true) == journal.anchor.workspaceKey)
        check(storage.key(journal.directory, true) == journal.anchor.journalKey)
        check(SafLocalFiles.version(storage, anchor) == anchorVersion)
        check(SafLocalFiles.version(storage, baseline) == baselineVersion)
        if (candidateVersion == null) check(storage.attributes(candidate) == null)
        else check(SafLocalFiles.version(storage, candidate) == candidateVersion)
    }

    private fun digest(operation: SafOperationJournal.Operation, name: String, poll: () -> Unit) =
        storage.describe(journal.file(operation, name), limits.recordBytes, poll).sha256

    companion object {
        internal const val CURRENT = "checkpoint.json"
        internal const val CANDIDATE = "checkpoint-next.json"
        private const val MAGIC = 0x53414631
        private const val SECTION_MAGIC = 0x53414632
        private const val PRIVATE = "private"
        private const val CUSTODY = "custody"
        private fun isDigest(value: String?) = value?.matches(Regex("[0-9a-f]{64}")) == true
        private fun add(a: SafRetirementCharge, b: SafRetirementCharge) = SafRetirementCharge(
            Math.addExact(a.operations, b.operations), Math.addExact(a.metadata, b.metadata),
            Math.addExact(a.index, b.index), Math.addExact(a.payload, b.payload))
    }
}
