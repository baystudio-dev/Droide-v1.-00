package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentImageGenerationTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    private val openAi = AgentConfig(providerId = "openai", baseUrl = "https://api.openai.com/v1", apiKey = SECRET, model = "unchanged-chat-model")
    private val google = AgentConfig(providerId = "gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta", apiKey = SECRET, model = "unchanged-chat-model")

    @Test fun openAiUsesExistingAuthOneRequestAndDurableOutputWithoutSecretOrPixelsInToolText() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            var implicitImageDelivery = false
            val ctx = context(root, fake, openAi, published::add, imageSink = { implicitImageDelivery = true })
            val result = AgentTools.execute("generate_image", args("gpt-image-future-2099", "Draw a compact IDE icon", "low"), ctx)
            assertTrue(result.startsWith("IMAGE_GENERATED "))
            assertEquals(1, fake.requests.size)
            val request = fake.requests.single()
            assertEquals("https://api.openai.com/v1/images/generations", request.url.toString())
            assertEquals("Bearer $SECRET", request.header("Authorization"))
            assertNull(request.header("x-goog-api-key"))
            val body = requestPayload(request)
            assertEquals("gpt-image-future-2099", body.getValue("model").jsonPrimitive.content)
            assertEquals(JsonPrimitive(1), body["n"])
            assertEquals(JsonPrimitive("jpeg"), body["output_format"])
            assertEquals(JsonPrimitive("1024x1024"), body["size"])
            assertEquals(JsonPrimitive("low"), body["quality"])
            assertFalse(body.containsKey("response_format"))
            val ref = published.single()
            assertEquals(ref.path, Json.parseToJsonElement(result.removePrefix("IMAGE_GENERATED ")).jsonObject.getValue("path").jsonPrimitive.content)
            assertArrayEquals(png, ChatMediaStore(root).file(ref)!!.readBytes())
            assertEquals(listOf(ref), ChatMediaStore(root).ownerRefs("test-session"))
            assertFalse(result.contains(SECRET))
            assertFalse(result.contains(Base64.getEncoder().encodeToString(png)))
            assertFalse(result.contains("Draw a compact"))
            assertFalse(implicitImageDelivery)
            assertEquals("unchanged-chat-model", ctx.llmConfig.model)
        }
    }

    @Test(timeout = 20_000) fun generatedOriginalCanBecomePermanentProjectAssetWithoutAnotherProviderRequest() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            val permission = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)),
                PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/chat-media/blobs/*", PermEffect.ALLOW))))
            val ctx = context(root, fake, openAi, published::add, permissions = permission)
            val generated = AgentTools.execute("generate_image", args("gpt-image-future-2099", "Draw a logo"), ctx)
            assertTrue(generated, generated.startsWith("IMAGE_GENERATED "))
            val alias = Json.parseToJsonElement(generated.substringAfter(' ')).jsonObject.getValue("path").jsonPrimitive.content
            val saved = AgentTools.execute("save_media_to_project", buildJsonObject {
                put("path", alias); put("destination", "public/logo.png")
            }, ctx)
            assertTrue(saved, saved.startsWith("MEDIA_SAVED_TO_PROJECT "))
            assertEquals(1, fake.requests.size)
            val store = ChatMediaStore(root)
            store.cleanupExpired(published.single().expiresAt)
            assertNull(store.file(published.single()))
            assertArrayEquals(png, root.resolve("public/logo.png").readBytes())
            assertEquals("unchanged-chat-model", ctx.llmConfig.model)
        }
    }

    @Test fun googleUsesNativeInlineOutputAndExistingHeaderWithoutAKeyInTheUrl() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, googleReply(png, "image/png")))
            val published = mutableListOf<ChatMediaRef>()
            val result = AgentTools.execute("generate_image", args("gemini-future-image-2099-preview", "Draw a window"), context(root, fake, google, published::add))
            assertTrue(result.startsWith("IMAGE_GENERATED"))
            val request = fake.requests.single()
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-future-image-2099-preview:generateContent", request.url.toString())
            assertEquals(SECRET, request.header("x-goog-api-key"))
            assertNull(request.url.query)
            assertNull(request.header("Authorization"))
            assertEquals(Json.parseToJsonElement("[\"TEXT\",\"IMAGE\"]"), requestPayload(request).getValue("generationConfig").jsonObject["responseModalities"])
            assertEquals("image/png", published.single().mediaType)
            assertFalse(result.contains(SECRET))
            assertEquals("unchanged-chat-model", google.model)
        }
    }

    @Test fun modelIdsAreNotVersionPinnedButGooglePathInjectionAndNonCanonicalIdsFailBeforeTransport() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            assertTrue(AgentTools.execute("generate_image", args("new-image-family-2099", "Draw"), context(root, fake, openAi, published::add)).startsWith("IMAGE_GENERATED"))
            val body = requestPayload(fake.requests.single())
            assertEquals("new-image-family-2099", body.getValue("model").jsonPrimitive.content)
            assertFalse(body.containsKey("output_format"))
            assertFalse(body.containsKey("quality"))
            for (model in listOf(" gemini-image", "gemini-image ", "../private", "gemini/image?key=$SECRET", "models/gemini-image", "gemini\nimage")) {
                assertTrue(AgentTools.execute("generate_image", args(model, "Draw"), context(root, fake, google, published::add)).startsWith("ERROR"))
            }
            assertEquals(1, fake.requests.size)
        }
    }

    @Test fun unsupportedProviderEndpointOrMissingCredentialHasZeroCallsAndNoFallback() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            for (config in listOf(openAi.copy(apiKey = ""), openAi.copy(providerId = "anthropic"), openAi.copy(providerId = "missing-provider"),
                openAi.copy(baseUrl = "https://proxy.example/v1"), openAi.copy(baseUrl = "http://api.openai.com/v1"),
                openAi.copy(baseUrl = "https://api.openai.com/v1?key=$SECRET"), google.copy(baseUrl = "https://other.googleapis.com/v1beta"))) {
                assertFalse(AgentImageGeneration.supports(config))
                assertTrue(AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, fake, config, published::add)).startsWith("ERROR"))
            }
            assertTrue(AgentImageGeneration.supports(openAi))
            assertTrue(AgentImageGeneration.supports(google.copy(baseUrl = "https://generativelanguage.googleapis.com/v1")))
            assertTrue(fake.requests.isEmpty())
            assertTrue(published.isEmpty())
        }
    }

    @Test fun everyNonBuildModeAndEditOrNetworkDenyBlocksBeforeGeneration() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            for (mode in AgentMode.entries.filter { it != AgentMode.BUILD }) {
                assertTrue(AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, fake, openAi, published::add, mode = mode)).startsWith("DENIED"))
            }
            for (action in listOf("edit", "webfetch")) {
                val permissions = PermissionEngine(listOf(PermRule(action, "*", PermEffect.DENY), PermRule("*", "*", PermEffect.ALLOW)))
                assertTrue(AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, fake, openAi, published::add, permissions = permissions)).startsWith("DENIED"))
            }
            assertTrue(fake.requests.isEmpty())
            assertTrue(published.isEmpty())
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        }
    }

    @Test fun generationSurfaceRequiresProviderCapabilityMediaAndEditNetworkVisibility() {
        val root = Files.createTempDirectory("droide-generation-surface-").toFile()
        try {
            fun visible(mode: AgentMode, media: Boolean, generation: Boolean, permissions: PermissionEngine? = null) =
                AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), mode = mode, mediaAvailable = media,
                    generationAvailable = generation, permissions = permissions).any {
                    it.jsonObject.getValue("function").jsonObject["name"] == JsonPrimitive("generate_image")
                }
            for (mode in AgentMode.entries) for (media in listOf(false, true)) for (generation in listOf(false, true))
                assertEquals(mode == AgentMode.BUILD && media && generation, visible(mode, media, generation))
            for (action in listOf("edit", "webfetch"))
                assertFalse(visible(AgentMode.BUILD, true, true, PermissionEngine(listOf(PermRule(action, "*", PermEffect.DENY)))))
            // Remote generation reads no workspace file and does not require image-input capability.
            assertTrue(visible(AgentMode.BUILD, true, true, PermissionEngine(listOf(PermRule("read", "*", PermEffect.DENY)))))
            assertEquals("edit", AgentTools.actionOf("generate_image"))
        } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun legacyDallEOptionsDoNotIncludeUnsupportedGptImageParameters() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            assertTrue(AgentTools.execute("generate_image", args("dall-e-2", "Draw"), context(root, fake, openAi, published::add)).startsWith("IMAGE_GENERATED"))
            assertTrue(AgentTools.execute("generate_image", args("dall-e-3", "Draw", "high"), context(root, fake, openAi, published::add)).startsWith("IMAGE_GENERATED"))
            val first = requestPayload(fake.requests[0])
            val second = requestPayload(fake.requests[1])
            assertEquals(JsonPrimitive("b64_json"), first["response_format"])
            assertFalse(first.containsKey("quality"))
            assertEquals(JsonPrimitive("hd"), second["quality"])
            assertFalse(first.containsKey("output_format"))
            assertFalse(second.containsKey("output_format"))
        }
    }

    @Test fun HttpFailureAndTransportFailureAreSingleCallAndNeverPersistProviderBodyOrSecrets() = runBlocking {
        workspace { root ->
            val published = mutableListOf<ChatMediaRef>()
            for (code in listOf(302, 400, 401, 403, 404, 429, 503)) {
                val fake = FakeTransport(AgentImageGeneration.Reply(code, "private error $SECRET base64=${Base64.getEncoder().encodeToString(png)}".toByteArray()))
                val result = AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, fake, openAi, published::add))
                assertTrue(result.startsWith("ERROR"))
                assertEquals(1, fake.requests.size)
                assertFalse(result.contains(SECRET))
                assertFalse(result.contains("private error"))
                assertFalse(result.contains(Base64.getEncoder().encodeToString(png)))
            }
            val disconnected = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)), failure = IllegalStateException("transport $SECRET"))
            val result = AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, disconnected, openAi, published::add))
            assertEquals(1, disconnected.requests.size)
            assertTrue(result.contains("No automatic retry"))
            assertFalse(result.contains(SECRET))
            assertTrue(published.isEmpty())
        }
    }

    @Test fun remoteImageUrlIsNotFetchedAndOnlyFirstInlineImageIsPublished() = runBlocking {
        workspace { root ->
            val published = mutableListOf<ChatMediaRef>()
            val url = FakeTransport(AgentImageGeneration.Reply(200, """{"data":[{"url":"https://elsewhere.example/file?secret=$SECRET"}]}""".toByteArray()))
            val result = AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, url, openAi, published::add))
            assertTrue(result.startsWith("ERROR"))
            assertTrue(result.contains("not fetched automatically"))
            assertFalse(result.contains(SECRET))
            assertEquals(1, url.requests.size)
            val multiple = buildJsonObject { put("data", buildJsonArray {
                add(buildJsonObject { put("b64_json", Base64.getEncoder().encodeToString(png)) })
                add(buildJsonObject { put("b64_json", "unsupported second data ignored") })
            }) }.toString().toByteArray()
            val fake = FakeTransport(AgentImageGeneration.Reply(200, multiple))
            assertTrue(AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, fake, openAi, published::add)).startsWith("IMAGE_GENERATED"))
            assertEquals(1, fake.requests.size)
            assertEquals(1, published.size)
        }
    }

    @Test fun invalidHeaderBase64MimeAndMalformedResponseNeverPublishMedia() = runBlocking {
        workspace { root ->
            val published = mutableListOf<ChatMediaRef>()
            val replies = listOf(
                openAiReply("not an image".toByteArray()) to openAi,
                """{"data":[{"b64_json":"invalid%%base64"}]}""".toByteArray() to openAi,
                """{"data":[{"b64_json":""}]}""".toByteArray() to openAi,
                "private invalid JSON $SECRET".toByteArray() to openAi,
                googleReply(png, "image/jpeg") to google,
                openAiReply("GIF89aunsupported animated generation".toByteArray()) to openAi,
            )
            for ((body, config) in replies) {
                val fake = FakeTransport(AgentImageGeneration.Reply(200, body))
                val result = AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, fake, config, published::add))
                assertTrue(result.startsWith("ERROR"))
                assertFalse(result.contains(SECRET))
                assertEquals(1, fake.requests.size)
            }
            assertTrue(published.isEmpty())
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        }
    }

    @Test fun jpegPngAndWebpHeadersAreAcceptedWithMatchingTypedCopies() = runBlocking {
        workspace { root ->
            val published = mutableListOf<ChatMediaRef>()
            for ((bytes, type) in listOf(png to "image/png", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0) to "image/jpeg",
                "RIFF0000WEBPheader".toByteArray() to "image/webp")) {
                val fake = FakeTransport(AgentImageGeneration.Reply(200, googleReply(bytes, type)))
                assertTrue(AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, fake, google, published::add)).startsWith("IMAGE_GENERATED"))
                assertEquals(type, published.last().mediaType)
                assertArrayEquals(bytes, ChatMediaStore(root).file(published.last())!!.readBytes())
            }
        }
    }

    @Test fun responseAndDecodedImageLimitsRejectOversizedPayloadsBeforePublication() = runBlocking {
        workspace { root ->
            val published = mutableListOf<ChatMediaRef>()
            val tooLargeResponse = FakeTransport(AgentImageGeneration.Reply(200, ByteArray((AgentImageGeneration.MAX_RESPONSE_BYTES + 1).toInt())))
            assertTrue(AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, tooLargeResponse, openAi, published::add)).contains("twelve MiB"))
            val largeImage = ByteArray((AgentImageGeneration.MAX_IMAGE_BYTES + 1).toInt()).apply { png.copyInto(this) }
            val tooLargeImage = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(largeImage)))
            assertTrue(AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, tooLargeImage, openAi, published::add)).contains("eight MiB"))
            assertEquals(1, tooLargeResponse.requests.size)
            assertEquals(1, tooLargeImage.requests.size)
            assertTrue(published.isEmpty())
        }
    }

    @Test fun argumentLimitsAndMalformedCredentialFailBeforeAnyTransportWithoutEchoingSecrets() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            for (input in listOf(args("image-model", ""), args("image-model", "x".repeat(4_001)), args("image-model", "Draw", SECRET),
                buildJsonObject { put("prompt", "Draw") }, buildJsonObject { put("model", 123); put("prompt", "Draw") })) {
                val result = AgentTools.execute("generate_image", input, context(root, fake, openAi, published::add))
                assertTrue(result.startsWith("ERROR"))
                assertFalse(result.contains(SECRET))
            }
            val result = AgentTools.execute("generate_image", args("gpt-image-new", "Draw"), context(root, fake, openAi.copy(apiKey = "$SECRET\ninvalid"), published::add))
            assertTrue(result.startsWith("ERROR"))
            assertFalse(result.contains(SECRET))
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test fun generationCancellationPropagatesAndCallbackFailureReclaimsNewOwnedCopy() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)), CancellationException("user stopped"))
            var cancelled = false
            try {
                AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, fake, openAi, { fail("no publish on stop") }))
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(1, fake.requests.size)
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
            val complete = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val result = AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, complete, openAi, { error("callback secret $SECRET") }))
            assertTrue(result.startsWith("ERROR"))
            assertFalse(result.contains(SECRET))
            assertTrue(ChatMediaStore(root).ownerRefs("test-session").isEmpty())
        }
    }

    @Test fun generatedOutputExpiresAfterSevenDaysAndLastOwnerDeletionReclaimsOnlyCopy() = runBlocking {
        workspace { root ->
            var now = System.currentTimeMillis()
            val store = ChatMediaStore(root, clock = { now })
            root.resolve("project-original.txt").writeText("untouched")
            val fake = FakeTransport(AgentImageGeneration.Reply(200, openAiReply(png)))
            val published = mutableListOf<ChatMediaRef>()
            val generation = AgentImageGeneration(fake, storeFactory = { store })
            assertTrue(AgentTools.execute("generate_image", args("image-model", "Draw"), context(root, fake, openAi, published::add, generation = generation)).startsWith("IMAGE_GENERATED"))
            val ref = published.single()
            assertEquals(now + ChatMediaStore.RETENTION_MS, ref.expiresAt)
            val physical = requireNotNull(store.file(ref))
            assertTrue(physical.exists())
            now = ref.expiresAt
            assertNull(store.file(ref))
            assertEquals(1, store.cleanupExpired())
            assertFalse(physical.exists())
            assertEquals("untouched", root.resolve("project-original.txt").readText())
        }
    }

    private class FakeTransport(private val reply: AgentImageGeneration.Reply, private val failure: Exception? = null) : AgentImageGeneration.Transport {
        val requests = mutableListOf<Request>()
        override suspend fun execute(provider: AiProvider, request: Request): AgentImageGeneration.Reply {
            assertTrue(provider.id in setOf("openai", "gemini"))
            requests += request
            failure?.let { throw it }
            return reply
        }
    }

    private fun args(model: String, prompt: String, quality: String? = null) = buildJsonObject {
        put("model", model); put("prompt", prompt); quality?.let { put("quality", it) }
    }
    private fun requestPayload(request: Request): JsonObject {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }
    private fun openAiReply(bytes: ByteArray) = buildJsonObject { put("data", buildJsonArray {
        add(buildJsonObject { put("b64_json", Base64.getEncoder().encodeToString(bytes)) })
    }) }.toString().toByteArray()
    private fun googleReply(bytes: ByteArray, mime: String) = buildJsonObject { put("candidates", buildJsonArray {
        add(buildJsonObject { put("content", buildJsonObject { put("parts", buildJsonArray {
            add(buildJsonObject { put("text", "ignored provider text with $SECRET") })
            add(buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", mime); put("data", Base64.getEncoder().encodeToString(bytes)) }) })
        }) }) })
    }) }.toString().toByteArray()

    @Test fun imageStatusDoesNotCallProviderAndModelDiscoveryDoesNotGenerateOrSwitchChatModel() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(200, """{"data":[{"id":"text-chat"},{"id":"gpt-image-current"}]}""".toByteArray()))
            val c = context(root, fake, openAi, {})
            val status = AgentTools.execute("generate_image", buildJsonObject { put("operation", "status") }, c)
            assertTrue(status.contains("unchanged-chat-model")); assertEquals(0, fake.requests.size)
            val models = AgentTools.execute("generate_image", buildJsonObject { put("operation", "models") }, c)
            assertTrue(models, models.contains("gpt-image-current")); assertFalse(models.contains("text-chat"))
            assertEquals(1, fake.requests.size); assertEquals("GET", fake.requests.single().method)
            assertTrue(fake.requests.single().url.toString().endsWith("/models")); assertEquals("unchanged-chat-model", c.llmConfig.model)
        }
    }

    @Test fun image500IdentifiesImageStageAndNeverRetriesOrEchoesBody() = runBlocking {
        workspace { root ->
            val fake = FakeTransport(AgentImageGeneration.Reply(500, "private error $SECRET".toByteArray(), "req-123"))
            val result = AgentTools.execute("generate_image", args("gpt-image-current", "Draw"), context(root, fake, openAi, {}))
            assertTrue(result.contains("Image provider service failed (HTTP 500)")); assertTrue(result.contains("req-123"))
            assertTrue(result.contains("Completion is unknown")); assertFalse(result.contains(SECRET)); assertEquals(1, fake.requests.size)
            val chat = ProviderFailure.describe(500, "The server had an error while processing your request. Sorry about that!")
            assertTrue(chat.contains("model response")); assertFalse(chat.contains("Image provider"))
        }
    }

    @Test fun explicitCompatibleImageApiIsBoundToRuntimeProviderAndCannotChangeItsEndpoint() = runBlocking {
        workspace { root ->
            val provider = ProviderRegistry.findById("openai")!!.copy(id = "test-images", baseUrl = "https://images.example/v1")
            val snapshot = ProviderRegistry.prepareConfigured(listOf(provider))
            val config = openAi.copy(providerId = provider.id, baseUrl = provider.baseUrl)
            var calls = 0
            val generation = AgentImageGeneration(AgentImageGeneration.Transport { selected, request ->
                calls++; assertEquals(provider.id, selected.id); assertEquals(provider.baseUrl + "/images/generations", request.url.toString())
                assertEquals(JsonPrimitive("b64_json"), requestPayload(request)["response_format"]); assertNull(requestPayload(request)["size"])
                AgentImageGeneration.Reply(200, openAiReply(png))
            })
            val base = context(root, FakeTransport(AgentImageGeneration.Reply(500, byteArrayOf())), config, {})
            fun ctx(url: String) = AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
                base.perms, base.mode, base.llm, config.copy(baseUrl = url), root, base.todos, base.sessionId, base.lsp,
                onMediaReady = {}, appContext = RuntimeEnvironment.getApplication(), imageGeneration = generation, providerConfiguration = snapshot)
            val arguments = JsonObject(args("new-image-model", "Draw") + ("api" to JsonPrimitive("openai_images")))
            assertTrue(AgentTools.execute("generate_image", arguments, ctx(provider.baseUrl)).startsWith("IMAGE_GENERATED"))
            assertTrue(AgentTools.execute("generate_image", arguments, ctx("https://unrelated.example/v1")).startsWith("ERROR"))
            assertEquals(1, calls)
        }
    }

    @Test fun googleSnakeCaseInlineImageAliasIsAccepted() = runBlocking {
        workspace { root ->
            val body = googleReply(png, "image/png").toString(Charsets.UTF_8).replace("inlineData", "inline_data").replace("mimeType", "mime_type").toByteArray()
            val fake = FakeTransport(AgentImageGeneration.Reply(200, body))
            assertTrue(AgentTools.execute("generate_image", args("gemini-current-image", "Draw"), context(root, fake, google, {})).startsWith("IMAGE_GENERATED"))
        }
    }

    private fun context(root: File, fake: FakeTransport, config: AgentConfig, sink: (ChatMediaRef) -> Unit,
        mode: AgentMode = AgentMode.BUILD,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
        imageSink: ((List<AgentImageRef>) -> Unit)? = null,
        generation: AgentImageGeneration = AgentImageGeneration(fake),
    ): AgentTools.Ctx {
        val base = agentWorkflowContext(root, permissions = permissions, mode = mode)
        return AgentTools.Ctx(base.files, base.terminal, base.git, base.approvals, base.questions, base.history,
            base.perms, base.mode, base.llm, config, root, base.todos, base.sessionId, base.lsp,
            onImageReady = imageSink, onMediaReady = sink, appContext = RuntimeEnvironment.getApplication(), imageGeneration = generation,
            mediaRunId = java.util.UUID.randomUUID().toString(), mediaRequestCurrent = { true })
    }
    private suspend fun workspace(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-image-generation-").toFile()
        try { block(root) } finally {
            PathSecurity.deleteTreeNoFollow(ChatMediaStore(root).storageDirectory)
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
    companion object { private const val SECRET = "fake-provider-credential-never-log" }
}
