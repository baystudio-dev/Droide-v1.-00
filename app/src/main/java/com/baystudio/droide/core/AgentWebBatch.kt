package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** A fixed worker pool bounds queued work as well as active transfers. */
internal object AgentWebBatch {
    const val MAX_SOURCES = 8
    private const val WORKERS = 3

    suspend fun execute(kind: String, field: String, inputs: List<String>,
        key: (String) -> String = { it }, onComplete: (Int, Int) -> Unit = { _, _ -> },
        operation: suspend (String) -> JsonObject): JsonObject = coroutineScope {
        val unique = linkedMapOf<String, Int>()
        val values = mutableListOf<String>()
        val firstIndices = mutableListOf<Int>()
        val positions = inputs.mapIndexed { index, input -> unique.getOrPut(key(input)) {
            values += input; firstIndices += index; values.lastIndex
        } }
        val next = AtomicInteger()
        val completedCount = AtomicInteger()
        val completed = arrayOfNulls<JsonObject>(values.size)
        repeat(minOf(WORKERS, values.size)) {
            launch {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= values.size) break
                    completed[index] = outcome { operation(values[index]) }
                    onComplete(completedCount.addAndGet(positions.count { it == index }), inputs.size)
                }
            }
        }
        // coroutineScope waits for workers before the result is read below.
        // Build the envelope outside this scope so no incomplete slot is observable.
        BatchWork(kind, field, inputs, positions, firstIndices, completed)
    }.let { work ->
        val results = work.inputs.mapIndexed { index, input ->
            val position = work.positions[index]
            JsonObject(requireNotNull(work.completed[position]) + buildJsonObject {
                put("index", index); put(work.field, input)
                if (work.firstIndices[position] != index) put("duplicate_of", work.firstIndices[position])
            })
        }
        val succeeded = results.count { it["success"] == JsonPrimitive(true) }
        buildJsonObject {
            put("success", succeeded == results.size); put("kind", work.kind)
            put("partial_success", succeeded in 1 until results.size)
            put("success_count", succeeded); put("failure_count", results.size - succeeded)
            put(if (work.kind == "search_batch") "searches" else "results", JsonArray(results))
            put("note", "Results follow input order; index identifies the requested input. Duplicate inputs share one retrieval. Each item has its own success or error.")
        }
    }

    private data class BatchWork(val kind: String, val field: String, val inputs: List<String>,
        val positions: List<Int>, val firstIndices: List<Int>, val completed: Array<JsonObject?>)

    private suspend fun outcome(operation: suspend () -> JsonObject): JsonObject = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (denied: AgentWebPermissionDenied) { failure("permission_denied", denied.result) }
    catch (unavailable: AgentWebSearchUnavailable) {
        JsonObject(failure("search", unavailable.message.orEmpty()) + buildJsonObject {
            put("attempts", AgentWebResearch.attempts(unavailable.attempts))
            put("browser_fallbacks", JsonArray(unavailable.fallbacks.map(::JsonPrimitive)))
        })
    }
    catch (problem: Exception) { failure("web_error", problem.message ?: "Web operation failed") }
    catch (problem: LinkageError) { failure("web_error", "Android web component unavailable: ${problem.message.orEmpty().take(300)}") }

    private fun failure(kind: String, message: String) = buildJsonObject {
        put("success", false); put("kind", kind); put("error", message.take(1_000))
        if (kind == "permission_denied") put("permission_denied", true)
    }

    /** Truncate content, never the batch structure or the reference needed to continue a page. */
    fun bounded(batch: JsonObject): String {
        val raw = batch.toString()
        if (raw.length <= AgentWebDocuments.MAX_OUTPUT_CHARS) return raw
        val field = if (batch["kind"] == JsonPrimitive("search_batch")) "searches" else "results"
        val items = batch.getValue(field).jsonArray
        val budget = (AgentWebDocuments.MAX_OUTPUT_CHARS - 1_024) / items.size.coerceAtLeast(1)
        return JsonObject(batch + mapOf(
            field to JsonArray(items.map { compact(it.jsonObject, budget) }),
            "output_truncated" to JsonPrimitive(true),
            "note" to JsonPrimitive("Some content or metadata was omitted to fit the batch. Continue each page with ref_id and next_start_line, using single open if needed. An omitted URL is available through single open(ref_id). Input index always identifies the request."),
        )).toString()
    }

    private fun compact(source: JsonObject, budget: Int): JsonObject {
        val out = source.toMutableMap()
        fun size() = JsonObject(out).toString().length
        if (size() <= budget) return source
        out["output_truncated"] = JsonPrimitive(true)
        for (key in listOf("note", "title", "attempts", "browser_fallbacks")) {
            if (size() <= budget) break
            if (out.remove(key) != null) out["metadata_omitted"] = JsonPrimitive(true)
        }
        val originalLines = out["lines"] as? JsonArray
        if (originalLines != null && size() > budget) {
            val start = (out["start_line"] as? JsonPrimitive)?.intOrNull ?: 0
            fun retain(count: Int) {
                out["lines"] = JsonArray(originalLines.take(count))
                out["end_line"] = JsonPrimitive(start + count)
                // Including zero lines is safe: the same offset can be read in a single open.
                if (count < originalLines.size) out["next_start_line"] = JsonPrimitive(start + count)
            }
            var low = 0; var high = originalLines.size
            while (low < high) {
                val middle = (low + high + 1) / 2
                retain(middle)
                if (size() <= budget) low = middle else high = middle - 1
            }
            retain(low)
        }
        val hits = out["results"] as? JsonArray
        if (hits != null && size() > budget) {
            // Preserve returned search identities first; snippets remain discovery context.
            out["results"] = JsonArray(hits.map { JsonObject(it.jsonObject - "snippet" - "title") })
            out["snippets_omitted"] = JsonPrimitive(true)
            if (size() > budget) {
                out["results"] = JsonArray(out.getValue("results").jsonArray.map {
                    JsonObject(it.jsonObject - "url" + ("url_omitted" to JsonPrimitive(true)))
                })
            }
        }
        // Long requested URLs can be identified without echoing them: index is stable even on errors.
        for (key in listOf("requested_url", "url", "requested_query")) {
            if (size() <= budget) break
            if (out.remove(key) != null) out["${key}_omitted"] = JsonPrimitive(true)
        }
        if (size() > budget && out["error"] is JsonPrimitive) {
            out["error"] = JsonPrimitive(out.getValue("error").jsonPrimitive.content.take(160))
            out["error_truncated"] = JsonPrimitive(true)
        }
        // Header/title-derived metadata must not displace durable source/continuation information.
        val durable = setOf("success", "kind", "index", "duplicate_of", "requested_ref_id", "requested_query",
            "ref_id", "url", "requested_url", "url_omitted", "requested_url_omitted", "requested_query_omitted", "lines", "results",
            "start_line", "end_line", "next_start_line", "total_lines", "source_incomplete", "cached", "error",
            "permission_denied", "error_truncated", "output_truncated", "snippets_omitted")
        for (key in out.keys.filter { it !in durable }.sortedByDescending { out[it].toString().length }) {
            if (size() <= budget) break
            out.remove(key)
        }
        return JsonObject(out)
    }
}
