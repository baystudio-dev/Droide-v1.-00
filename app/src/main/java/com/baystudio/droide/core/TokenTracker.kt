package com.baystudio.droide.core

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// ESTIMATED remains readable in previously saved step metadata; new usage is never guessed.
@Serializable
enum class UsageAuthority { AUTHORITATIVE, ESTIMATED, MIXED, UNKNOWN }

data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val total: Int = 0,
    val reasoning: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val costUsd: Double = 0.0,

    val tokenAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
    val costAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
    val requestCount: Long = 0,
    val knownTokenRequestCount: Long = 0,
    val knownCostRequestCount: Long = 0,
    val breakdownComplete: Boolean = false,
    val breakdownMismatch: Boolean = false,

    val tokenOverflow: Boolean = false,
    val costOverflow: Boolean = false,
)

data class TokenTrackerSnapshot(
    val usage: TokenUsage = TokenUsage(),
    val history: List<Pair<String, TokenUsage>> = emptyList(),
    val warning: String? = null,
)

class TokenTracker {
    private val lock = Any()
    private val state = MutableStateFlow(TokenTrackerSnapshot())
    val snapshot: StateFlow<TokenTrackerSnapshot> = state.asStateFlow()
    // Scope-free compatibility views. Multi-field consumers should collect snapshot once.
    val usage: StateFlow<TokenUsage> = SnapshotView(snapshot) { it.usage }
    val history: StateFlow<List<Pair<String, TokenUsage>>> = SnapshotView(snapshot) { it.history }
    val warning: StateFlow<String?> = SnapshotView(snapshot) { it.warning }

    fun add(model: String, completionResponse: String): TokenUsage = addForProvider("", model, completionResponse)

    /** Provider identity admits only that provider's documented usage aliases. Never infer it from the model. */
    fun addForProvider(providerId: String, model: String, completionResponse: String): TokenUsage {
        return addSample(model, parseProviderUsage(providerId, completionResponse))
    }

    /** One already-validated request sample, including interrupted provider work. */
    internal fun addSample(model: String, sample: TokenUsage): TokenUsage {
        synchronized(lock) {
            val before = state.value
            val accumulated = accumulate(before.usage, sample)
            val label = model.take(80).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().ifEmpty { "model" }
            val entry = "Request ${accumulated.requestCount} · $label" to sample

            state.value = before.copy(usage = accumulated, history = (before.history + entry).takeLast(200))
        }
        return sample
    }

    /** Kept for callers compiled against the previous API; request content is never inspected. */
    @Suppress("UNUSED_PARAMETER")
    fun add(model: String, promptText: String, completionResponse: String): TokenUsage = add(model, completionResponse)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        internal fun parseProviderUsage(providerId: String, response: String): TokenUsage {
            val reported = runCatching {
                (json.parseToJsonElement(response) as? JsonObject)?.get("usage") as? JsonObject
            }.getOrNull() ?: return TokenUsage(requestCount = 1)
            val prompt = reported.first("prompt_tokens", "input_tokens").count()
            val completion = reported.first("completion_tokens", "output_tokens").count()
            val coreSum = if (prompt != null && completion != null) prompt.toLong() + completion else null
            val total = if (reported.containsKey("total_tokens")) reported["total_tokens"].count()
                else coreSum?.takeIf { it <= Int.MAX_VALUE }?.toInt()

            val completionDetailValue = reported.first("completion_tokens_details", "output_tokens_details")
            val promptDetailValue = reported.first("prompt_tokens_details", "input_tokens_details")
            val completionDetails = completionDetailValue as? JsonObject
            val promptDetails = promptDetailValue as? JsonObject
            val reasoningValue = completionDetails?.get("reasoning_tokens")
            val cacheReadValue = promptDetails?.get("cached_tokens")
                ?: reported.first("cache_read_input_tokens", "cache_read_tokens")
                ?: when {
                    promptDetailValue != null && promptDetails == null -> null
                    providerId == "deepseek" -> reported["prompt_cache_hit_tokens"]
                    providerId == "together" -> reported["cached_tokens"]
                    else -> null
                }
            val cacheWriteValue = promptDetails?.get("cache_write_tokens")
                ?: reported.first("cache_creation_input_tokens", "cache_write_input_tokens")
            val reasoning = reasoningValue.count()
            val cacheRead = cacheReadValue.count()
            val cacheWrite = cacheWriteValue.count()
            val detailsValid = (completionDetailValue == null || completionDetails != null) &&
                (promptDetailValue == null || promptDetails != null) &&
                (reasoningValue == null || reasoning != null) &&
                (cacheReadValue == null || cacheRead != null) &&
                (cacheWriteValue == null || cacheWrite != null)
            // Canonical details are subsets, but cache read/write need not be disjoint.
            val subsetsValid = (reasoning == null || completion == null || reasoning <= completion) &&
                (cacheRead == null || prompt == null || cacheRead <= prompt) &&
                (cacheWrite == null || prompt == null || cacheWrite <= prompt)
            val cost = (reported.first("cost", "cost_usd") as? JsonPrimitive)
                ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }
            return TokenUsage(
                prompt = prompt ?: 0,
                completion = completion ?: 0,
                total = total ?: 0,
                reasoning = reasoning ?: 0,
                cacheRead = cacheRead ?: 0,
                cacheWrite = cacheWrite ?: 0,
                costUsd = cost ?: 0.0,
                tokenAuthority = if (total != null) UsageAuthority.AUTHORITATIVE else UsageAuthority.UNKNOWN,
                costAuthority = if (cost != null) UsageAuthority.AUTHORITATIVE else UsageAuthority.UNKNOWN,
                requestCount = 1,
                knownTokenRequestCount = if (total != null) 1 else 0,
                knownCostRequestCount = if (cost != null) 1 else 0,
                breakdownComplete = prompt != null && completion != null && detailsValid && subsetsValid,
                breakdownMismatch = total != null && coreSum != null && total.toLong() != coreSum,
            )
        }

        // Presence has priority over fallback aliases: invalid canonical data cannot become a valid
        // alias.
        private fun JsonObject.first(vararg names: String): JsonElement? = names.firstOrNull(::containsKey)?.let(::get)
        private fun JsonElement?.count(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }
            ?.intOrNull?.takeIf { it >= 0 }
    }

    private fun accumulate(current: TokenUsage, sample: TokenUsage): TokenUsage {
        val requests = current.requestCount + 1
        val tokenRequests = current.knownTokenRequestCount + sample.knownTokenRequestCount
        val costRequests = current.knownCostRequestCount + sample.knownCostRequestCount
        val prompt = current.prompt.toLong() + sample.prompt
        val completion = current.completion.toLong() + sample.completion
        val total = current.total.toLong() + sample.total
        val reasoning = current.reasoning.toLong() + sample.reasoning
        val cacheRead = current.cacheRead.toLong() + sample.cacheRead
        val cacheWrite = current.cacheWrite.toLong() + sample.cacheWrite
        val componentOverflow = listOf(prompt, completion, reasoning, cacheRead, cacheWrite).any { it > Int.MAX_VALUE }
        val tokenOverflow = current.tokenOverflow || total > Int.MAX_VALUE
        val cost = current.costUsd + sample.costUsd
        val costOverflow = current.costOverflow || !cost.isFinite()
        return TokenUsage(
            prompt = prompt.saturatedInt(),
            completion = completion.saturatedInt(),
            total = total.saturatedInt(),
            reasoning = reasoning.saturatedInt(),
            cacheRead = cacheRead.saturatedInt(),
            cacheWrite = cacheWrite.saturatedInt(),
            costUsd = if (cost.isFinite()) cost else Double.MAX_VALUE,
            tokenAuthority = coverage(requests, tokenRequests, tokenOverflow),
            costAuthority = coverage(requests, costRequests, costOverflow),
            requestCount = requests,
            knownTokenRequestCount = tokenRequests,
            knownCostRequestCount = costRequests,
            breakdownComplete = (current.requestCount == 0L || current.breakdownComplete) && sample.breakdownComplete && !componentOverflow,
            breakdownMismatch = current.breakdownMismatch || sample.breakdownMismatch,
            tokenOverflow = tokenOverflow,
            costOverflow = costOverflow,
        )
    }

    private fun Long.saturatedInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun coverage(requests: Long, known: Long, overflow: Boolean): UsageAuthority = when {
        known == 0L -> UsageAuthority.UNKNOWN
        known == requests && !overflow -> UsageAuthority.AUTHORITATIVE
        else -> UsageAuthority.MIXED
    }

    fun warnQuota(msg: String) { synchronized(lock) { state.value = state.value.copy(warning = msg) } }
    fun clearWarning() { synchronized(lock) { state.value = state.value.copy(warning = null) } }
    fun reset() { synchronized(lock) { state.value = TokenTrackerSnapshot() } }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class SnapshotView<T>(
    private val source: StateFlow<TokenTrackerSnapshot>,
    private val select: (TokenTrackerSnapshot) -> T,
) : StateFlow<T> {
    override val value: T get() = select(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map(select).distinctUntilChanged().collect(collector)
        error("StateFlow collection unexpectedly completed")
    }
}
