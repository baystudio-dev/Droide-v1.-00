package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Reports-only proposal. Existing API, synthetic usage chunks, no HTTP client or calls. */
class GroqUsageWrapperR31BaselineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private val finalUsage = obj("""{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}""")
    private fun stream() = LlmClient.StreamAccumulator(json).apply {
        accept("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
    }
    private fun sample(stream: LlmClient.StreamAccumulator) = TokenTracker().add("fixture", stream.completedResponse())
    private fun nested(value: JsonObject, rootUsage: JsonElement? = null, includeRoot: Boolean = false) = buildJsonObject {
        put("choices", JsonArray(emptyList()))
        if (includeRoot) put("usage", rootUsage ?: JsonNull)
        put("x_groq", buildJsonObject { put("usage", value) })
    }.toString()

    @Test fun groqNestedOnlyFinalUsageReachesTracker() {
        val stream = stream()
        stream.accept(nested(finalUsage))
        val value = sample(stream)
        assertEquals(150, value.total)
        assertEquals(100, value.prompt)
        assertEquals(50, value.completion)
        assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
        assertEquals(1L, value.requestCount)
    }

    @Test fun nullCanonicalUsageAllowsNamespacedGroqUsage() {
        val stream = stream()
        stream.accept(nested(finalUsage, includeRoot = true))
        assertEquals(150, sample(stream).total)
    }

    @Test fun duplicateCanonicalAndGroqUsageAreNotAdded() {
        val stream = stream()
        stream.accept(nested(finalUsage, finalUsage, includeRoot = true))
        assertEquals(150, sample(stream).total)
    }

    @Test fun canonicalUsageTakesPrecedenceOverConflictingNamespacedUsage() {
        val stream = stream()
        stream.accept(nested(obj("""{"prompt_tokens":900,"completion_tokens":99,"total_tokens":999}"""), finalUsage, includeRoot = true))
        assertEquals(150, sample(stream).total)
    }

    @Test fun reportedCanonicalZeroIsNotReplacedByNestedNonzeroUsage() {
        val stream = stream()
        stream.accept(nested(finalUsage, obj("""{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}"""), includeRoot = true))
        val value = sample(stream)
        assertEquals(0, value.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
    }

    @Test fun namespacedSnapshotsReplaceRatherThanAccumulate() {
        val stream = stream()
        stream.accept(nested(obj("""{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}""")))
        stream.accept(nested(finalUsage))
        assertEquals(150, sample(stream).total)
    }

    @Test fun compoundModelBreakdownDoesNotAddToReportedAggregate() {
        val stream = stream()
        stream.accept("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150},"x_groq":{"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150},"usage_breakdown":{"models":[{"usage":{"prompt_tokens":700,"completion_tokens":300,"total_tokens":1000}}]}}}""")
        assertEquals(150, sample(stream).total)
    }

    @Test fun malformedPresentCanonicalUsageIsNotSilentlyReplacedWithNestedUsage() {
        val stream = stream()
        stream.accept(nested(finalUsage, JsonPrimitive("invalid"), includeRoot = true))
        assertEquals(UsageAuthority.UNKNOWN, sample(stream).tokenAuthority)
    }

    @Test fun laterMalformedCanonicalReportInvalidatesEarlierUsageInsteadOfReusingIt() {
        val stream = stream()
        stream.accept("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""")
        stream.accept(nested(finalUsage, JsonPrimitive("invalid"), includeRoot = true))
        val value = sample(stream)
        assertEquals(UsageAuthority.UNKNOWN, value.tokenAuthority)
        assertEquals(0, value.total)
    }

    @Test fun laterMalformedNestedReportInvalidatesEarlierUsageInsteadOfReusingIt() {
        val stream = stream()
        stream.accept("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""")
        stream.accept("""{"choices":[],"usage":null,"x_groq":{"usage":"invalid"}}""")
        val value = sample(stream)
        assertEquals(UsageAuthority.UNKNOWN, value.tokenAuthority)
        assertEquals(0, value.total)
    }

    @Test fun absentOrNullUsageWithoutANewReportRetainsLatestKnownSnapshot() {
        val stream = stream()
        stream.accept("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""")
        stream.accept("""{"choices":[]}""")
        stream.accept("""{"choices":[],"usage":null}""")
        stream.accept("""{"choices":[],"usage":null,"x_groq":{"usage":null}}""")
        val value = sample(stream)
        assertEquals(150, value.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
    }
}
