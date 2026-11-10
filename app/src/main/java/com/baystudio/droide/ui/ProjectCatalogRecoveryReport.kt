package com.baystudio.droide.ui

import com.baystudio.droide.core.AnchoredWorkspaceFiles
import com.baystudio.droide.core.CanonicalFileOperationJournal
import com.baystudio.droide.core.CanonicalFileSnapshot
import kotlinx.serialization.json.*

internal object ProjectCatalogRecoveryReport {
    private const val MAX_CHARACTERS = 131_072
    private val json = Json { prettyPrint = true }

    fun history(history: CanonicalFileOperationJournal.RecoveryMetadata, completedAtMillis: Long): String =
        report("history", completedAtMillis) {
            put("pending", history.pending?.let(::intent) ?: JsonNull)
            put("recorded_completions", JsonArray(history.completed.map(::intent)))
            put("retention_usage", history.retentionUsage?.let { usage -> buildJsonObject {
                put("completed_entries", usage.completedEntries)
                put("max_entries", usage.maxEntries)
                put("retained_bytes", usage.retainedBytes)
                put("max_retained_bytes", usage.maxRetainedBytes)
                put("metadata_bytes", usage.metadataBytes)
                put("max_metadata_bytes", usage.maxMetadataBytes)
                put("next_record_reserve_bytes", usage.nextRecordReserveBytes)
            } } ?: JsonNull)
            put("capacity_observation_only", true)
            put("authorizes_release", false)
            put("default_migration_ready", false)
            put("file_observations_included", false)
        }

    fun operation(review: CanonicalFileOperationJournal.OperationRecoveryReview, completedAtMillis: Long): String =
        report("operation", completedAtMillis) {
            put("intent", intent(review.intent))
            put("archived", review.archived)
            put("catalog", file(review.canonicalPath, review.canonical, null))
            put("custody", JsonArray(review.custody.map { copy ->
                buildJsonObject {
                    put("role", copy.role.name)
                    put("file", file("${review.custodyPath}/${copy.name}", copy.observed, copy.recordedVersion))
                }
            }))
        }

    fun unavailable(operationId: String?, reason: String, completedAtMillis: Long): String =
        report("unavailable", completedAtMillis) {
            put("operation_id", operationId?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason)
            put("file_observations_included", false)
        }

    private fun report(kind: String, completedAtMillis: Long, content: JsonObjectBuilder.() -> Unit): String {
        require(completedAtMillis >= 0)
        val value = buildJsonObject {
            put("format", "droide-project-catalog-inspection")
            put("version", 1)
            put("scope", "project_catalog_only")
            put("kind", kind)
            put("inspection_completed_at_epoch_ms", completedAtMillis)
            put("contains_file_contents", false)
            put("authorizes_recovery", false)
            put("notice", "Inspection metadata only, not a backup. Observations may no longer match current files.")
            content()
        }
        return json.encodeToString(JsonObject.serializer(), value).also {
            check(it.length <= MAX_CHARACTERS) {
                "This report is too large to copy. Open a single change and try again."
            }
        }
    }

    private fun intent(value: CanonicalFileOperationJournal.Intent): JsonObject = buildJsonObject {
        put("id", value.id)
        put("relative_path", value.path)
        put("operation", value.kind.name)
        put("recorded_stage", value.phase.name)
        put("before", version(value.before))
        put("proposed_content", buildJsonObject {
            put("size_bytes", value.proposal.size)
            put("sha256", value.proposal.sha256)
            put("mode_octal", "0${value.proposal.mode.toString(8)}")
        })
    }

    private fun file(path: String, observed: CanonicalFileOperationJournal.FileObservation,
                     recorded: CanonicalFileSnapshot.Version?): JsonObject = buildJsonObject {
        put("path", path)
        put("observed_kind", observed.kind.name)
        put("recorded_version", version(recorded))
        put("observed_version", version(observed.version))
        put("matches_recorded_version", recorded?.let {
            JsonPrimitive(observed.kind == AnchoredWorkspaceFiles.Kind.FILE && observed.version == it)
        } ?: JsonNull)
    }

    private fun version(value: CanonicalFileSnapshot.Version?): JsonElement = value?.let {
        buildJsonObject {
            put("identity", it.identity)
            put("size_bytes", it.size)
            put("sha256", it.sha256)
            put("mode_octal", "0${it.mode.toString(8)}")
        }
    } ?: JsonNull
}
