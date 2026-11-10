package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyStageLocatorCompatibilityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)

    @Test fun frozenV1WireAndEveryCompleteHashChainRemainByteIdentical() {
        val layouts = listOf("prepared", "unknown", "committed", "retired-prepared",
            "retired-prepared-only", "retired-committed", "retired-committed-only", "retired-committed-partial")
        for (layout in layouts) project { root ->
            val wire = legacyWire(root)
            assertEquals(wire.intentText, json.encodeToString(wire.intent))
            assertEquals(wire.attemptText, json.encodeToString(wire.attempt))
            assertEquals(wire.receiptText, json.encodeToString(wire.receipt))
            assertEquals(wire.sealText, json.encodeToString(wire.seal))
            assertEquals(wire.retiredText, json.encodeToString(wire.retired))
            assertEquals(wire.retiredPreparedText, json.encodeToString(wire.retiredPrepared))
            val fields = when (layout) {
                "prepared" -> mapOf("intent.json" to wire.intentText)
                "unknown" -> mapOf("intent.json" to wire.intentText, "attempt.json" to wire.attemptText)
                "committed" -> wire.complete
                "retired-prepared" -> mapOf("intent.json" to wire.intentText, "retired.json" to wire.retiredPreparedText)
                "retired-prepared-only" -> mapOf("retired.json" to wire.retiredPreparedText)
                "retired-committed" -> wire.complete + ("retired.json" to wire.retiredText)
                "retired-committed-only" -> mapOf("retired.json" to wire.retiredText)
                else -> mapOf("receipt.json" to wire.receiptText, "retired.json" to wire.retiredText)
            }
            persist(root, wire.intent.id, fields)
            val original = snapshot(root)
            val journal = journal(root)
            val recovery = journal.recovery(scope)
            assertNull(layout, recovery.blockingProblem)
            if (layout.startsWith("retired-committed")) {
                assertTrue(layout, recovery.records.isEmpty())
            } else {
                val record = recovery.records.single()
                assertEquals(1, record.intent.version)
                assertNull(record.intent.plannedStagePath)
                assertEquals(wire.intent.id, record.intent.id)
                val expected = when (layout) {
                    "prepared" -> ProjectCopyJournal.Status.PREPARED
                    "committed" -> ProjectCopyJournal.Status.COMMITTED
                    else -> ProjectCopyJournal.Status.UNKNOWN
                }
                assertEquals(layout, expected, record.status)
                assertNull(record.problem)
                assertFalse(record.active)
                if (expected == ProjectCopyJournal.Status.COMMITTED) {
                    assertEquals(wire.preparation.stageIdentity, record.receipt!!.destinationIdentity)
                    assertEquals(wire.preparation.sha256, record.receipt.preparation.sha256)
                } else assertNull(record.receipt)
                if (expected == ProjectCopyJournal.Status.UNKNOWN) {
                    assertNull(journal.inspect(scope, record.intent.id).current.target)
                    assertEquals(expected, journal.recovery(scope).records.single().status)
                }
            }
            assertEquals(layout, original, snapshot(root))
        }
    }

    @Test fun defaultAdmissionStillProducesTheOriginalV1IntentWire() = project { root ->
        val journal = journal(root)
        journal.begin(owner("default"), "asset.bin").use { lease ->
            assertEquals(1, lease.intent.version)
            assertNull(lease.intent.plannedStagePath)
            val text = recordDirectory(root, lease.intent.id).resolve("intent.json").readText()
            val old = V1Intent(id = lease.intent.id, canonicalRoot = lease.intent.canonicalRoot,
                rootIdentity = lease.intent.rootIdentity, owner = V1Owner("EXPLORER_IMPORT", null,
                    lease.intent.owner.runId, lease.intent.owner.sourceId), destination = lease.intent.destination,
                createdAt = lease.intent.createdAt)
            assertEquals(json.encodeToString(old), text)
            assertFalse(Json.parseToJsonElement(text).jsonObject.containsKey("plannedStagePath"))
        }
    }

    @Test fun plannedAdmissionPersistsV2LocationBeforeAnyStageIsCreated() = project { root ->
        val journal = journal(root)
        journal.begin(owner("planned"), "assets/asset.bin", planStage = true).use { lease ->
            val expected = "assets/.droide-tmp-${lease.intent.id}"
            assertEquals(2, lease.intent.version)
            assertEquals(expected, lease.intent.plannedStagePath)
            val file = recordDirectory(root, lease.intent.id).resolve("intent.json")
            val stored = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(JsonPrimitive(2), stored["version"])
            assertEquals(JsonPrimitive(expected), stored["plannedStagePath"])
            assertFalse(root.resolve(expected).exists())
        }
        val before = snapshot(root)
        val restored = journal(root).recovery(scope).records.single()
        assertEquals(2, restored.intent.version)
        assertEquals("assets/.droide-tmp-${restored.intent.id}", restored.intent.plannedStagePath)
        assertEquals(before, snapshot(root))
    }

    @Test fun valid500CharacterDestinationKeepsIts547CharacterPlannedSibling() {
        for (character in listOf("p", "界")) project { root ->
            val parents = MutableList(31) { character.repeat(15) }.apply { this[0] = character.repeat(18) }
            val destination = (parents + "x").joinToString("/")
            assertEquals(500, destination.length)
            assertEquals(32, destination.split('/').size)
            val journal = ProjectCopyJournal(root, historyLimit = 1, processEpoch = UUID.randomUUID().toString())
            var transactionId: String? = null
            journal.begin(owner("long-path"), destination, planStage = true).use { lease ->
                transactionId = lease.intent.id
                val plan = requireNotNull(lease.intent.plannedStagePath)
                assertEquals(547, plan.length)
                assertEquals(destination.substringBeforeLast('/'), plan.substringBeforeLast('/'))
                assertEquals(".droide-tmp-${lease.intent.id}", plan.substringAfterLast('/'))
                val stage = root.resolve(plan)
                Files.createDirectories(stage.parentFile.toPath())
                stage.writeText("old")
                val preparation = StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                    lease.intent.rootIdentity, destination, 3, sha("old"), ProjectCopyEvidence.identity(stage).key)
                lease.beforePublish(preparation)
                Files.createLink(root.resolve(destination).toPath(), stage.toPath())
                lease.onPublished(StreamPublicationReceipt(preparation, preparation.stageIdentity))
            }
            val record = journal(root).recovery(scope).records.single()
            assertEquals(547, requireNotNull(record.intent.plannedStagePath).length)
            assertNull(record.problem)
            assertEquals(ProjectCopyJournal.Status.COMMITTED, record.status)
            assertEquals(32, requireNotNull(record.stageParents).size)
            assertEquals(listOf("") + parents, record.stageParents!!.map { it.segment })
            recordDirectory(root, requireNotNull(transactionId)).listFiles()!!.forEach { assertTrue(it.length() <= 32_768L) }
            Files.delete(root.resolve(requireNotNull(record.intent.plannedStagePath)).toPath())
            journal.begin(owner("after-long-path"), "later.bin").close()
            assertFalse(recordDirectory(root, requireNotNull(transactionId)).exists())
            assertEquals("old", root.resolve(destination).readText())
        }
    }

    @Test fun v1CannotSmuggleANewLocatorNullFieldOrUnknownSchema() {
        for (damage in listOf("locator", "explicit-null", "unknown-key", "duplicate-key", "future-version")) project { root ->
            val wire = legacyWire(root)
            val text = when (damage) {
                "locator" -> wire.intentText.dropLast(1) + ",\"plannedStagePath\":\".droide-tmp-${wire.intent.id}\"}"
                "explicit-null" -> wire.intentText.dropLast(1) + ",\"plannedStagePath\":null}"
                "unknown-key" -> wire.intentText.dropLast(1) + ",\"futureField\":true}"
                "duplicate-key" -> wire.intentText.replaceFirst("{", "{\"version\":1,")
                else -> wire.intentText.replaceFirst("\"version\":1", "\"version\":99")
            }
            persist(root, wire.intent.id, mapOf("intent.json" to text))
            assertRejectedWithoutMutation(root)
        }
    }

    @Test fun v2RequiresTheExactSafeSiblingAndRejectsMissingOrFutureSchema() {
        val damages = listOf("missing", "null", "wrong-parent", "wrong-name", "wrong-id", "traversal",
            "absolute", "backslash", "future-version", "unknown-key", "duplicate-key")
        for (damage in damages) project { root ->
            val lease = journal(root).begin(owner("planned"), "assets/item.bin", planStage = true)
            lease.close()
            val file = recordDirectory(root, lease.intent.id).resolve("intent.json")
            val original = file.readText()
            val fields = Json.parseToJsonElement(original).jsonObject.toMutableMap()
            when (damage) {
                "missing" -> fields.remove("plannedStagePath")
                "null" -> fields["plannedStagePath"] = JsonNull
                "wrong-parent" -> fields["plannedStagePath"] = JsonPrimitive("other/.droide-tmp-${lease.intent.id}")
                "wrong-name" -> fields["plannedStagePath"] = JsonPrimitive("assets/stage-${lease.intent.id}")
                "wrong-id" -> fields["plannedStagePath"] = JsonPrimitive("assets/.droide-tmp-${UUID.randomUUID()}")
                "traversal" -> fields["plannedStagePath"] = JsonPrimitive("assets/../.droide-tmp-${lease.intent.id}")
                "absolute" -> fields["plannedStagePath"] = JsonPrimitive("/tmp/.droide-tmp-${lease.intent.id}")
                "backslash" -> fields["plannedStagePath"] = JsonPrimitive("assets\\.droide-tmp-${lease.intent.id}")
                "future-version" -> fields["version"] = JsonPrimitive(3)
                "unknown-key" -> fields["futureField"] = JsonPrimitive(true)
            }
            file.writeText(if (damage == "duplicate-key") original.replaceFirst("{", "{\"version\":2,")
                else JsonObject(fields).toString())
            assertRejectedWithoutMutation(root)
        }
    }

    @Test fun oldReceiptHashMismatchStaysUnknownWithoutPromotingOrRewritingIt() = project { root ->
        val wire = legacyWire(root)
        val corruptReceipt = wire.receiptText.replace(sha(wire.attemptText), "f".repeat(64))
        persist(root, wire.intent.id, wire.complete + ("receipt.json" to corruptReceipt))
        val before = snapshot(root)
        val journal = journal(root)
        val record = journal.recovery(scope).records.single()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, record.status)
        assertNotNull(record.problem)
        assertNull(record.receipt)
        assertNull(record.intent.plannedStagePath)
        assertTrue(runCatching { journal.inspect(scope, record.intent.id) }.isFailure)
        assertEquals(before, snapshot(root))
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun attemptedParentBindingsRequireTheExactVersionedOrderedSchema() {
        val damages = listOf("version1-with-parents", "missing", "null", "empty-chain", "duplicate",
            "out-of-order", "missing-parent", "root-mismatch", "wrong-prefix", "empty-segment", "empty-identity",
            "control-identity", "future-version", "unknown-parent-key", "legacy-intent-attempt2", "v1-component-with-parents")
        for (damage in damages) project { root ->
            root.resolve("assets/sub").mkdirs()
            val planned = damage != "legacy-intent-attempt2" && damage != "v1-component-with-parents"
            val originalOwner = owner("attempt-schema")
            val journal = journal(root)
            val destination = "assets/sub/asset.bin"
            val lease = journal.begin(originalOwner, destination, planStage = planned)
            val path = lease.intent.plannedStagePath ?: "assets/sub/.droide-tmp-${lease.intent.id}"
            val stage = root.resolve(path).apply { writeText("old") }
            val stageIdentity = ProjectCopyEvidence.identity(stage)
            val preparation = StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                lease.intent.rootIdentity, destination, 3, sha("old"), stageIdentity.key)
            lease.beforePublish(preparation)
            lease.close()
            val originalRecord = journal(root).recovery(scope).records.single()
            assertNull(originalRecord.problem)
            if (planned) assertEquals(3, requireNotNull(originalRecord.stageParents).size)
            else assertNull(originalRecord.stageParents)
            val attempt = recordDirectory(root, lease.intent.id).resolve("attempt.json")
            val fields = Json.parseToJsonElement(attempt.readText()).jsonObject.toMutableMap()
            val parents = fields["stageParents"] as? JsonArray ?: JsonArray(listOf(
                JsonObject(linkedMapOf("segment" to JsonPrimitive(""), "identity" to JsonPrimitive(lease.intent.rootIdentity))),
                JsonObject(linkedMapOf("segment" to JsonPrimitive("assets"), "identity" to JsonPrimitive(ProjectCopyEvidence.identity(root.resolve("assets")).key))),
                JsonObject(linkedMapOf("segment" to JsonPrimitive("sub"), "identity" to JsonPrimitive(ProjectCopyEvidence.identity(root.resolve("assets/sub")).key)))))
            assertEquals(listOf("", "assets", "sub"), parents.map { (it.jsonObject.getValue("segment") as JsonPrimitive).content })
            assertTrue(parents.all { it.jsonObject.keys == setOf("segment", "identity") })
            fun replaceParent(index: Int, key: String, value: String) = JsonArray(parents.mapIndexed { current, parent ->
                if (current == index) JsonObject(parent.jsonObject.toMutableMap().apply { this[key] = JsonPrimitive(value) })
                else parent
            })
            when (damage) {
                "version1-with-parents" -> fields["version"] = JsonPrimitive(1)
                "missing" -> fields.remove("stageParents")
                "null" -> fields["stageParents"] = JsonNull
                "empty-chain" -> fields["stageParents"] = JsonArray(emptyList())
                "duplicate" -> fields["stageParents"] = JsonArray(parents + parents[1])
                "out-of-order" -> fields["stageParents"] = JsonArray(listOf(parents[0], parents[2], parents[1]))
                "missing-parent" -> fields["stageParents"] = JsonArray(listOf(parents[0], parents[1]))
                "root-mismatch" -> fields["stageParents"] = replaceParent(0, "identity", "different-project-root")
                "wrong-prefix" -> fields["stageParents"] = replaceParent(1, "segment", "foreign")
                "empty-segment" -> fields["stageParents"] = replaceParent(1, "segment", "")
                "empty-identity" -> fields["stageParents"] = replaceParent(1, "identity", "")
                "control-identity" -> fields["stageParents"] = replaceParent(1, "identity", "unsafe\nidentity")
                "future-version" -> fields["version"] = JsonPrimitive(99)
                "unknown-parent-key" -> fields["stageParents"] = replaceParent(1, "futureField", "unknown")
                "legacy-intent-attempt2" -> { fields["version"] = JsonPrimitive(2); fields["stageParents"] = parents }
                "v1-component-with-parents" -> fields["stageParents"] = parents
            }
            attempt.writeText(JsonObject(fields).toString())
            val before = snapshot(root)
            val restarted = journal(root)
            val recovery = restarted.recovery(scope)
            assertNull(damage, recovery.blockingProblem)
            val record = recovery.records.single()
            assertEquals(damage, ProjectCopyJournal.Status.UNKNOWN, record.status)
            assertNotNull(damage, record.problem)
            assertNull(record.receipt)
            assertTrue(damage, runCatching { restarted.inspect(scope, record.intent.id) }.isFailure)
            assertTrue(damage, runCatching {
                restarted.begin(originalOwner.copy(runId = "later-manual-request"), destination).close()
            }.isFailure)
            assertEquals(damage, before, snapshot(root))
            assertEquals(stageIdentity, ProjectCopyEvidence.identity(stage))
            assertEquals("old", stage.readText())
            assertFalse(root.resolve(destination).exists())
        }
    }

    private fun assertRejectedWithoutMutation(root: File) {
        val before = snapshot(root)
        val journal = journal(root)
        val recovery = journal.recovery(scope)
        assertTrue(recovery.blockingProblem != null || recovery.records.any { it.problem != null })
        assertTrue(runCatching { journal.begin(owner("unrelated"), "unrelated.bin") }.isFailure)
        assertEquals(before, snapshot(root))
        assertFalse(root.resolve("unrelated.bin").exists())
    }

    private fun journal(root: File) = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString())
    private fun owner(id: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
        "picker-$id", ProjectCopyJournal.explorerSourceId("content://compatibility/$id"))
    private fun recordDirectory(root: File, id: String) = root.resolve(".droide/copy-recovery/$id")
    private fun persist(root: File, id: String, contents: Map<String, String>) {
        val directory = recordDirectory(root, id)
        Files.createDirectories(directory.toPath())
        contents.forEach { (name, text) -> directory.resolve(name).writeText(text) }
    }
    private fun snapshot(root: File) = root.resolve(".droide/copy-recovery").walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to sha(it.readText()) }
    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun quote(text: String) = json.encodeToString(text)
    private inline fun project(block: (File) -> Unit) {
        val root = Files.createTempDirectory("copy-locator-wire-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun legacyWire(root: File): FrozenWire {
        val id = "11111111-1111-1111-1111-111111111111"
        val rootPath = root.canonicalPath
        val rootIdentity = ProjectCopyEvidence.root(root).second
        val sourceId = "a".repeat(64)
        val owner = V1Owner("EXPLORER_IMPORT", null, "legacy-picker", sourceId)
        val intent = V1Intent(id = id, canonicalRoot = rootPath, rootIdentity = rootIdentity,
            owner = owner, destination = "asset.bin", createdAt = 42)
        val preparation = V1Preparation("22222222-2222-2222-2222-222222222222", rootPath,
            rootIdentity, "asset.bin", 3, sha("old"), "legacy-stage-inode")
        val ownerText = "{\"kind\":\"EXPLORER_IMPORT\",\"sessionId\":null,\"runId\":\"legacy-picker\",\"sourceId\":${quote(sourceId)}}"
        val intentText = "{\"version\":1,\"id\":${quote(id)},\"canonicalRoot\":${quote(rootPath)}," +
            "\"rootIdentity\":${quote(rootIdentity)},\"owner\":$ownerText,\"destination\":\"asset.bin\",\"createdAt\":42}"
        val preparationText = "{\"publicationId\":${quote(preparation.publicationId)},\"canonicalRoot\":${quote(rootPath)}," +
            "\"rootIdentity\":${quote(rootIdentity)},\"relativePath\":\"asset.bin\",\"bytes\":3," +
            "\"sha256\":${quote(preparation.sha256)},\"stageIdentity\":\"legacy-stage-inode\"}"
        val attempt = V1Attempt(intentHash = sha(intentText), preparation = preparation)
        val attemptText = "{\"version\":1,\"intentHash\":${quote(sha(intentText))},\"preparation\":$preparationText}"
        val publication = V1Publication(preparation, preparation.stageIdentity)
        val receipt = V1Receipt(attemptHash = sha(attemptText), publication = publication)
        val receiptText = "{\"version\":1,\"attemptHash\":${quote(sha(attemptText))},\"publication\":{" +
            "\"preparation\":$preparationText,\"destinationIdentity\":\"legacy-stage-inode\"}}"
        val seal = V1Seal(id = id, intentHash = sha(intentText), attemptHash = sha(attemptText), receiptHash = sha(receiptText))
        val sealText = "{\"version\":1,\"id\":${quote(id)},\"intentHash\":${quote(sha(intentText))}," +
            "\"attemptHash\":${quote(sha(attemptText))},\"receiptHash\":${quote(sha(receiptText))}}"
        val retired = V1Retired(intent = intent, attempt = attempt, receipt = receipt, seal = seal)
        val retiredText = "{\"version\":1,\"intent\":$intentText,\"attempt\":$attemptText," +
            "\"receipt\":$receiptText,\"seal\":$sealText}"
        val retiredPrepared = V1Retired(intent = intent)
        val retiredPreparedText = "{\"version\":1,\"intent\":$intentText,\"attempt\":null,\"receipt\":null,\"seal\":null}"
        return FrozenWire(intent, preparation, attempt, receipt, seal, retired, retiredPrepared,
            intentText, attemptText, receiptText, sealText, retiredText, retiredPreparedText)
    }

    private data class FrozenWire(val intent: V1Intent, val preparation: V1Preparation, val attempt: V1Attempt,
        val receipt: V1Receipt, val seal: V1Seal, val retired: V1Retired, val retiredPrepared: V1Retired,
        val intentText: String, val attemptText: String, val receiptText: String, val sealText: String,
        val retiredText: String, val retiredPreparedText: String) {
        val complete get() = mapOf("intent.json" to intentText, "attempt.json" to attemptText,
            "receipt.json" to receiptText, "seal.json" to sealText)
    }
    @Serializable private data class V1Owner(val kind: String, val sessionId: String?, val runId: String, val sourceId: String)
    @Serializable private data class V1Intent(val version: Int = 1, val id: String, val canonicalRoot: String,
        val rootIdentity: String, val owner: V1Owner, val destination: String, val createdAt: Long)
    @Serializable private data class V1Preparation(val publicationId: String, val canonicalRoot: String,
        val rootIdentity: String, val relativePath: String, val bytes: Long, val sha256: String, val stageIdentity: String)
    @Serializable private data class V1Attempt(val version: Int = 1, val intentHash: String, val preparation: V1Preparation)
    @Serializable private data class V1Publication(val preparation: V1Preparation, val destinationIdentity: String)
    @Serializable private data class V1Receipt(val version: Int = 1, val attemptHash: String, val publication: V1Publication)
    @Serializable private data class V1Seal(val version: Int = 1, val id: String, val intentHash: String,
        val attemptHash: String, val receiptHash: String)
    @Serializable private data class V1Retired(val version: Int = 1, val intent: V1Intent,
        val attempt: V1Attempt? = null, val receipt: V1Receipt? = null, val seal: V1Seal? = null)
}
