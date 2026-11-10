package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Existing-API source baseline; deliberately proves wrapping, not repaired Host behavior. */
class AcpCommandRoutingBaselineTest {
    @Test fun existingMediaEncoderPlacesGuidanceBeforeSlashAndKeepsOrdinaryPromptScope() {
        val raw = "/compact keep exact arguments"
        val content = AcpChatMedia.prompt(raw, emptyList(), emptyList(), File("."), AcpPromptCapabilities())
        assertEquals(1, content.size)
        val encoded = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(AgentRequestScope.forCliPrompt(raw), encoded)
        assertFalse("Pinned leading-slash command detection cannot see this as a command", encoded.trim().startsWith('/'))
        assertTrue(encoded.endsWith("[User message]\n$raw\n[/User message]"))
    }
}
