package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LspCMakeProfileTest {
    private fun initialize(encoding:JsonElement?=JsonPrimitive("utf-32"),formatter:Boolean=false):JsonElement=buildJsonObject {
        put("capabilities",buildJsonObject {
            encoding?.let{put("positionEncoding",it)}
            put("textDocumentSync",buildJsonObject{put("openClose",true);put("change",2);put("save",true)})
            put("completionProvider",buildJsonObject{put("triggerCharacters",buildJsonArray{add("{");add("(")})})
            put("hoverProvider",true);put("documentFormattingProvider",formatter)
        })
    }
    @Test fun routingDoesNotStealJsonOrCacheData() {
        assertTrue(LspCMakeProfile.supportsPath("src/CMakeLists.txt"));assertTrue(LspCMakeProfile.supportsPath("src/FindA.cmake"))
        for(path in listOf("CMakeCache.txt","CMakePresets.json","cmakelists.txt","x.txt"))assertFalse(LspCMakeProfile.supportsPath(path))
    }
    @Test fun genericEncodingRemainsUtf16AndCmakeRequiresExplicitUtf32() {
        assertEquals("utf-16",LspNegotiatedCapabilities.fromInitialize(initialize(null)).positionEncoding)
        assertTrue(runCatching{LspNegotiatedCapabilities.fromInitialize(initialize())}.isFailure)
        assertTrue(runCatching{LspNegotiatedCapabilities.fromInitialize(initialize(null),"utf-32",true)}.isFailure)
        assertTrue(runCatching{LspNegotiatedCapabilities.fromInitialize(initialize(JsonPrimitive(32)),"utf-32",true)}.isFailure)
        assertTrue(runCatching{LspNegotiatedCapabilities.fromInitialize(initialize(JsonPrimitive("UTF-32")),"utf-32",true)}.isFailure)
        assertTrue(runCatching{LspNegotiatedCapabilities.fromInitialize(initialize(JsonPrimitive(32)))}.isFailure)
        LspCMakeProfile.requireInitializeContract(initialize())
    }
    @Test fun formatterAdvertisementCannotDenyCompletionAndHoverOrEnableFormatting() {
        val result=initialize(formatter=true)
        LspCMakeProfile.requireInitializeContract(result)
        val native=LspNegotiatedCapabilities.fromInitialize(result,"utf-32",true)
        val filtered=LspCMakeProfile.supportedCapabilities(native)
        assertTrue(native.formatting);assertFalse(filtered.formatting);assertTrue(filtered.completion);assertTrue(filtered.hover)
        assertEquals(LspTextSyncPolicy.ChangeKind.INCREMENTAL,filtered.sync.change)
        assertFalse(filtered.definition);assertFalse(filtered.rename);assertFalse(filtered.codeAction);assertFalse(filtered.semanticTokens)
    }
    @Test fun exactCapabilitiesAndAbsoluteGuestOptions() {
        val base=LspProtocolContract.clientCapabilities();val caps=LspCMakeProfile.clientCapabilities(base)
        assertEquals("utf-16",base["general"]!!.jsonObject["positionEncodings"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("utf-32",caps["general"]!!.jsonObject["positionEncodings"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(caps["textDocument"]!!.jsonObject["completion"]!!.jsonObject["completionItem"]!!.jsonObject["snippetSupport"]!!.jsonPrimitive.boolean)
        assertEquals("/guest/build",LspCMakeProfile.initializationOptions("/usr/bin/cmake","/guest/build")["buildDirectory"]!!.jsonPrimitive.content)
        assertTrue(runCatching{LspCMakeProfile.initializationOptions("cmake","build")}.isFailure)
    }
}
