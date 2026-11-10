package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object ProviderFailure {
    private val json = Json { ignoreUnknownKeys = true }

    fun describe(status: Int, body: String): String {
        val detail = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error")
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> (root?.get("message") as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.trim()
        val lower = detail.lowercase()
        val limit = ProviderLimitPolicy.diagnose(status, body)
        val access = ProviderLimitPolicy.accessStatus(status, body)
        val hint = when {
            access == 401 -> "Authentication failed. Check the credential and selected provider."
            limit != null -> limit.hint
            access == 403 -> "Provider denied access. Check credential permissions, project and model access."
            status == 404 -> "Provider endpoint or model was not found. Refresh models and check the configured endpoint."
            status == 429 -> "Provider rate limit reached. Wait before retrying or select another model."
            status in setOf(400, 422) && ("function_declarations" in lower || "input_schema" in lower || "schema" in lower || "functiondeclarations" in lower) ->
                "Provider rejected a tool schema. Check the named tool's parameters, array items and required fields."
            status in setOf(400, 422) && ("thought_signature" in lower || "thoughtsignature" in lower) ->
                "Provider rejected the tool continuation signature. Retry in a new chat if this history predates the provider update."
            status in setOf(400, 422) -> "Provider rejected the request. Check model capabilities and request parameters."
            status in setOf(408, 504) -> "Provider request timed out. Retry when the service is available."
            status >= 500 -> "Provider service failed before a complete model response was received. Check provider status and model access before retrying."
            else -> "Provider request failed."
        }
        return "HTTP $status: $hint" + detail.takeIf { it.isNotBlank() }?.let { "\n${redact(it).take(500)}" }.orEmpty()
    }

    // Compatibility name: only identified non-immediate limits, not a credit-balance claim.
    fun isQuotaFailure(status: Int, body: String): Boolean =
        ProviderLimitPolicy.diagnose(status, body)?.blocksImmediateRetry == true

    fun limitWarning(failure: Throwable): String? = when (failure) {
        is LlmHttpException -> ProviderLimitPolicy.diagnose(failure.statusCode, failure.responseBody)?.hint
        is LlmProviderStreamException -> ProviderLimitPolicy.diagnose(failure.logicalStatus ?: 0, failure.responseBody)?.hint
        else -> null
    }

    fun redact(body: String, secrets: Collection<String> = emptyList()): String {
        var safe = body
        secrets.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }.forEach { secret ->
            safe = safe.replace(secret, "[redacted]")
            val escaped = JsonPrimitive(secret).toString().removeSurrounding("\"")
            if (escaped != secret) safe = safe.replace(escaped, "[redacted]")
        }
        safe = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]{8,}").replace(safe, "$1[redacted]")
        safe = Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}&]{8,}").replace(safe, "$1[redacted]")
        safe = Regex("(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{20,})").replace(safe, "[redacted]")
        return safe
    }

    fun requestSecrets(header: (String) -> String?): List<String> =
        listOf("Authorization", "x-api-key", "x-goog-api-key", "api-key", "x-amz-security-token").mapNotNull(header)
            .flatMap { listOf(it, it.removePrefix("Bearer ")) }
}
