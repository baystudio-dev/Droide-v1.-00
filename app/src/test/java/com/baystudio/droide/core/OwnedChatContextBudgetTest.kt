package com.baystudio.droide.core

import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OwnedChatContextBudgetTest {
    private val provider = AiProvider("owned-context-budget", "Budget", "https://example.test/v1", "model", false, false, "", "")
    private val target = TypedChatTarget(provider.id, "model", OwnedChatEndpointIdentity.fromValidatedUrl(provider.baseUrl + "/chat/completions")).runtime()
    @After fun cleanup() { ModelContextRegistry.forget(provider.id) }

    @Test fun base64ImagesUseImageAllowanceAndExactBytesAreStillSent() {
        ModelContextRegistry.remember(provider.id, "model", 12_000)
        val data = "data:image/png;base64," + "A".repeat(2_000_000)
        val message = buildJsonObject {
            put("role", "user"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Inspect this image" ) })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", data) }) })
            })
        }
        val options = buildJsonObject { put("model", "model"); put("max_tokens", 2_048) }
        val body = OwnedChatRequestBody.admit(options, listOf(SourceAwareChatHistoryWriter.Item.Legacy(message)), target, provider) {}
        val buffer = Buffer(); body.writeTo(buffer)
        assertEquals(body.contentLength(), buffer.size)
        assertEquals(message, Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("messages").jsonArray.single())
    }

    @Test fun unicodeBudgetIsCountedOnceInStreamedAndOrdinaryRequests() {
        ModelContextRegistry.remember(provider.id, "model", 12_000)
        val message = buildJsonObject { put("role", "user"); put("content", "漢".repeat(9_000)) }
        val options = buildJsonObject { put("model", "model"); put("max_tokens", 2_048) }

        assertTrue(OwnedChatRequestBody.admit(options, listOf(SourceAwareChatHistoryWriter.Item.Legacy(message)), target, provider) {}.contentLength() > 27_000)
        val huge = buildJsonObject { put("role", "user"); put("content", "漢".repeat(20_000)) }
        assertThrows(ProviderContextOverflowException::class.java) {
            OwnedChatRequestBody.admit(options, listOf(SourceAwareChatHistoryWriter.Item.Legacy(huge)), target, provider) {}
        }
    }
}
