package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal object AgentWebTools {
    suspend fun execute(name: String, args: JsonObject, context: AgentTools.Ctx,
        gate: suspend (String, String) -> String?): String = try {
        val owner = "${context.sessionId.orEmpty()}:${context.provenance.kind}:${context.provenance.taskId}"
        val web = context.webResearch
        val authorize: suspend (String, String) -> Unit = { action, resource ->
            gate(action, resource)?.let { throw AgentWebPermissionDenied(it) }
        }
        val mediaOwnerId = context.sessionId?.takeIf { it.isNotBlank() } ?: owner
        val images: (suspend (java.io.File) -> Boolean)? = if (context.onImageReady != null || context.onMediaReady != null) {
            { file ->
                val media = ChatMediaStore(context.workDir)
                val path = media.aliasForFile(file)
                val ref = requireNotNull(media.findByPath(path)) { "Web image is expired or unavailable" }
                val deliver = context.onImageReady
                val staged = if (deliver != null && VisionSupport.providerAcceptsImageUrl(context.llmConfig)) {
                    VisionSupport.stage(context.workDir, listOf(path), context.llmConfig, ownerId = mediaOwnerId).also(deliver)
                } else emptyList()
                context.onMediaReady?.invoke(ref)
                staged.isNotEmpty()
            }
        } else null
        val op = when (name) { "webfetch" -> "open"; "websearch" -> "search"; else -> string(args,"operation") ?: error("web requires operation") }
        fun progress(value: String) = context.onProgress(AgentToolProgress(detail = value))
        val result = when (op) {
            "status" -> web.status(owner)
            "search" -> {
                val queries = if (args["queries"] != null) {
                    require(args["query"] == null) { "Supply query or queries, not both" }
                    (args["queries"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("queries must be strings") }
                        ?: error("queries must be an array")
                } else listOf(string(args,"query") ?: error("search requires query or queries"))
                require(queries.size in 1..3 && queries.all { it.isNotBlank() }) { "Supply 1..3 nonempty queries" }
                val domains = args["domains"]?.let { value ->
                    (value as? JsonArray ?: error("domains must be an array")).map {
                        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("domains must be strings")
                    }
                }.orEmpty()
                val requests = queries.associateWith { query -> AgentWebSearch.Request(query,
                    string(args,"engine") ?: "auto", integer(args,"max_results",10), domains,
                    args["recency_days"]?.let { integer(args,"recency_days",0) }) }
                if (queries.size == 1) web.search(owner, requests.getValue(queries.single()), authorize, ::progress)
                else AgentWebBatch.execute("search_batch", "requested_query", queries,
                    onComplete = { done, total -> progress("Searching web queries · $done/$total complete") }) { query ->
                    web.search(owner, requests.getValue(query), authorize, ::progress)
                }
            }
            "open" -> {
                if (args["urls"] != null || args["ref_ids"] != null) {
                    require(name == "web") { "Batch opening requires web(operation=open)" }
                    require(args["url"] == null && args["ref_id"] == null &&
                        (args["urls"] == null) != (args["ref_ids"] == null)) {
                        "Supply exactly one of url, ref_id, urls or ref_ids"
                    }
                    val byUrl = args["urls"] != null
                    val key = if (byUrl) "urls" else "ref_ids"
                    val inputs = strings(args, key)
                    require(inputs.size in 1..AgentWebBatch.MAX_SOURCES && inputs.all { it.isNotBlank() }) {
                        "Supply 1..8 nonempty $key per batch; more sources may be read in later batches"
                    }
                    require(inputs.all { it.length <= if (byUrl) 8_192 else 128 }) { "$key contains an excessively long value" }
                    val start = integer(args,"start_line",0)
                    val count = integer(args,"num_lines",80)
                    require(start >= 0 && count in 1..200) { "Invalid zero-based start_line/num_lines" }
                    val refresh = boolean(args,"refresh",false)
                    progress("Reading ${inputs.size} web sources · up to 3 transfers at once")
                    AgentWebBatch.execute("open_batch", if (byUrl) "requested_url" else "requested_ref_id", inputs,
                        key = { input -> if (byUrl) runCatching { AgentWebUrl.normalize(input) }.getOrDefault(input) else input },
                        onComplete = { done, total -> progress("Reading web sources · $done/$total complete") }) { input ->
                        web.openText(owner, input.takeIf { byUrl }, input.takeUnless { byUrl }, start, count, refresh, authorize)
                    }
                } else {
                    progress("Reading web source")
                    web.open(owner, string(args,"url"), string(args,"ref_id"), integer(args,"start_line",0),
                        integer(args,"num_lines",80), boolean(args,"refresh",false), authorize, images, mediaOwnerId)
                }
            }
            "find" -> web.find(owner, required(args,"ref_id"), required(args,"query"), integer(args,"start_line",0),
                integer(args,"max_results",10), boolean(args,"case_sensitive",false), authorize)
            "links" -> web.links(owner, required(args,"ref_id"), integer(args,"start",0), integer(args,"num_results",10), authorize)
            "read_pdf" -> web.readPdf(owner, required(args,"ref_id"), integer(args,"page",0), integer(args,"start_line",0), integer(args,"num_lines",80), authorize)
            "pdf_screenshot" -> {
                progress("Rendering PDF page")
                web.screenshotPdf(owner, required(args,"ref_id"), integer(args,"page",0), authorize, images, mediaOwnerId)
            }
            else -> error("Unsupported web operation: $op")
        }
        if (result["kind"]?.jsonPrimitive?.contentOrNull in setOf("image", "pdf") &&
            result["path"]?.jsonPrimitive?.contentOrNull?.startsWith("${ChatMediaStoragePaths.VIRTUAL_ROOT}/blobs/") == true) {
            context.appContext?.let { ChatMediaCleanup.schedule(it, context.workDir) }
        }
        bounded(result)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (denied: AgentWebPermissionDenied) { denied.result }
    catch (unavailable: AgentWebSearchUnavailable) {
        bounded(buildJsonObject {
            put("success", false); put("kind", "search"); put("error", unavailable.message)
            put("attempts", AgentWebResearch.attempts(unavailable.attempts))
            put("browser_fallbacks", JsonArray(unavailable.fallbacks.map(::JsonPrimitive)))
        })
    } catch (failure: Exception) { webFailure(failure.message ?: "Web operation failed") }
    catch (failure: LinkageError) { webFailure("Android PDF/browser component unavailable: ${failure.message.orEmpty().take(300)}") }

    private fun webFailure(message: String) = buildJsonObject { put("success",false); put("kind","web_error"); put("error",message.take(1_000)) }.toString()
    private fun string(args: JsonObject, key: String): String? = args[key]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("$key must be a string")
    }
    private fun required(args: JsonObject, key: String): String = string(args,key)?.takeIf { it.isNotBlank() } ?: error("Missing $key")
    private fun strings(args: JsonObject, key: String): List<String> =
        (args[key] as? JsonArray ?: error("$key must be an array")).map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("$key must contain strings")
        }
    private fun integer(args: JsonObject, key: String, default: Int): Int = args[key]?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("$key must be an integer")
    } ?: default
    private fun boolean(args: JsonObject, key: String, default: Boolean): Boolean = args[key]?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("$key must be a boolean")
    } ?: default

    internal fun bounded(result: JsonObject): String {
        if (result["kind"] in setOf(JsonPrimitive("open_batch"), JsonPrimitive("search_batch"))) return AgentWebBatch.bounded(result)
        val raw = result.toString()
        if (raw.length <= AgentWebDocuments.MAX_OUTPUT_CHARS) return raw
        // A rare oversized batch/metadata response is an explicit valid-JSON preview, never half a JSON object.
        val retained = result.filterKeys { it in setOf("success","kind","ref_id","url","source_ref_id","next_start_line","source_incomplete") }
        var preview = raw.take(6_000)
        fun envelope() = JsonObject(retained + mapOf("output_truncated" to JsonPrimitive(true), "original_chars" to JsonPrimitive(raw.length),
            "preview" to JsonPrimitive(preview), "note" to JsonPrimitive("Narrow the query, request fewer results, or continue using ref_id/start_line. The preview is incomplete context.")))
        while (envelope().toString().length > AgentWebDocuments.MAX_OUTPUT_CHARS && preview.isNotEmpty()) preview = preview.take(preview.length/2)
        return envelope().toString()
    }

    /** AgentService must preserve structured source/continuation data after adding its evidence marker. */
    internal fun forAgentHistory(tool: String, annotatedResult: String): String {
        if (tool in setOf("web", "webfetch", "websearch", "browser")) {
            val raw = AgentToolResultSemantics.stripAnnotation(annotatedResult)
            val structured = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (structured != null) {
                val result = bounded(structured)
                return AgentToolResultSemantics.parseAnnotation(annotatedResult)?.annotate(tool, result) ?: result
            }
        }
        return AgentToolHistoryProjection.project(annotatedResult)
    }
}
