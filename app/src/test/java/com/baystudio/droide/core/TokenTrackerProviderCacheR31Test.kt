package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Reports-only future-API regression source. Not staged, compiled, or executed. */
class TokenTrackerProviderCacheR31Test {
    private fun response(fields: String) = """{"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,$fields}}"""
    private fun sample(provider: String, fields: String) =
        TokenTracker().addForProvider(provider, "fixture", response(fields))

    @Test fun deepSeekCacheHitIsScopedInputSubset() {
        val value = sample("deepseek", """"prompt_cache_hit_tokens":40,"prompt_cache_miss_tokens":60""")
        assertEquals(40, value.cacheRead)
        assertEquals(100, value.prompt)
        assertEquals(150, value.total)
        assertTrue(value.breakdownComplete)
        assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
    }

    @Test fun togetherFlatCacheIsScopedInputSubset() {
        val value = sample("together", """"cached_tokens":40""")
        assertEquals(40, value.cacheRead)
        assertEquals(150, value.total)
        assertTrue(value.breakdownComplete)
    }

    @Test fun aliasesNeverLeakToOtherIdsOrProviderlessCompatibilityApis() {
        val fields = """"prompt_cache_hit_tokens":40,"cached_tokens":50"""
        for (provider in listOf("", "openrouter", "custom", "groq", "DeepSeek", "deepseek ", "togetherai")) {
            assertEquals(provider, 0, sample(provider, fields).cacheRead)
        }
        assertEquals(0, TokenTracker().add("deepseek/deepseek-chat", response(fields)).cacheRead)
        assertEquals(0, TokenTracker().add("deepseek", "together", response(fields)).cacheRead)
        assertEquals(0, sample("deepseek", """"cached_tokens":40""").cacheRead)
        assertEquals(0, sample("together", """"prompt_cache_hit_tokens":40""").cacheRead)
    }

    @Test fun canonicalPresenceWinsIncludingInvalidCanonicalData() {
        for (provider in listOf("deepseek", "together")) {
            val aliases = """"prompt_cache_hit_tokens":40,"cached_tokens":50"""
            val canonical = sample(provider, """"prompt_tokens_details":{"cached_tokens":30},$aliases""")
            assertEquals(30, canonical.cacheRead)
            assertEquals(150, canonical.total)
            for (invalid in listOf("null", "-1", "\"30\"", "{}")) {
                val value = sample(provider, """"prompt_tokens_details":{"cached_tokens":$invalid},$aliases""")
                assertEquals("$provider / $invalid", 0, value.cacheRead)
                assertFalse("$provider / $invalid", value.breakdownComplete)
                assertEquals(150, value.total)
                assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
            }
            for (container in listOf("prompt_tokens_details", "input_tokens_details")) {
                for (invalid in listOf("null", "\"broken\"", "[]")) {
                    val value = sample(provider, "\"$container\":$invalid,$aliases")
                    assertEquals("$provider / $container / $invalid", 0, value.cacheRead)
                    assertFalse("$provider / $container / $invalid", value.breakdownComplete)
                    assertEquals(150, value.total)
                    assertEquals(UsageAuthority.AUTHORITATIVE, value.tokenAuthority)
                }
            }
        }
    }

    @Test fun providerAliasesReceiveExistingNumericAndSubsetValidation() {
        for ((provider, alias) in listOf("deepseek" to "prompt_cache_hit_tokens", "together" to "cached_tokens")) {
            val impossibleSubset = sample(provider, "\"$alias\":101")
            assertEquals(101, impossibleSubset.cacheRead)
            assertFalse(impossibleSubset.breakdownComplete)
            assertEquals(150, impossibleSubset.total)
            val negative = sample(provider, "\"$alias\":-1")
            assertEquals(0, negative.cacheRead)
            assertFalse(negative.breakdownComplete)
            assertEquals(UsageAuthority.AUTHORITATIVE, negative.tokenAuthority)
        }
    }

    @Test fun estimatedCostRemainsUnknownWhileExplicitZeroStaysKnown() {
        val estimated = sample("together", """"cached_tokens":40,"estimated_cost":0.25""")
        assertEquals(UsageAuthority.UNKNOWN, estimated.costAuthority)
        assertEquals(0.0, estimated.costUsd, 0.0)
        val explicit = sample("deepseek", """"prompt_cache_hit_tokens":40,"estimated_cost":0.25,"cost":0""")
        assertEquals(UsageAuthority.AUTHORITATIVE, explicit.costAuthority)
        assertEquals(0.0, explicit.costUsd, 0.0)
    }

    @Test fun eachEntryPointStillRecordsExactlyOneRequest() {
        val tracker = TokenTracker()
        val body = response(""""prompt_cache_hit_tokens":40""")
        tracker.addForProvider("deepseek", "fixture", body)
        tracker.add("fixture", body)
        tracker.add("fixture", "ignored private prompt", body)
        assertEquals(3L, tracker.usage.value.requestCount)
        assertEquals(3L, tracker.usage.value.knownTokenRequestCount)
        assertEquals(3, tracker.history.value.size)
        assertEquals(450, tracker.usage.value.total)
        assertEquals(40, tracker.usage.value.cacheRead)
        assertFalse(tracker.history.value.any { it.first.contains("private prompt") })
    }
}
