package com.baystudio.droide.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Credential endpoint diagnostics never expose raw JSON or a pre-clipped token prefix. */
internal object ProviderCredentialResponsePolicy {
    private val json = Json { ignoreUnknownKeys = true }
    private val knownCodes = setOf(
        "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
        "unsupported_grant_type", "invalid_scope", "access_denied", "temporarily_unavailable",
        "server_error", "authorization_pending", "slow_down", "expired_token",
        "bad_verification_code", "incorrect_client_credentials", "bad_credentials",
        "ExpiredToken", "ExpiredTokenException", "InvalidToken", "InvalidIdentityToken",
        "UnrecognizedClientException", "InvalidSignatureException", "AccessDeniedException",
        "UnauthorizedException",
    )

    fun parseObject(body: String, label: String, httpStatus: Int? = null): JsonObject {
        val transport = httpStatus?.let { " (HTTP $it)" }.orEmpty()
        val parsed = try { json.parseToJsonElement(body) }
        catch (_: SerializationException) { throw IllegalStateException("$label returned malformed JSON$transport") }
        return parsed as? JsonObject ?: throw IllegalStateException("$label returned invalid JSON$transport")
    }

    fun httpFailure(status: Int, body: String, label: String = "Credential endpoint"): IllegalStateException {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = obj?.get("error")
        val code = when (error) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["code"] as? JsonPrimitive)?.contentOrNull
            else -> (obj?.get("code") as? JsonPrimitive)?.contentOrNull
        }?.takeIf(knownCodes::contains)
        return IllegalStateException("$label HTTP $status" + code?.let { " ($it)" }.orEmpty() +
            ": authentication request failed. Check provider credentials and access.")
    }

    fun rejection(label: String, code: String): IllegalStateException = IllegalStateException(
        "$label rejected credentials" + code.takeIf(knownCodes::contains)?.let { " ($it)" }.orEmpty(),
    )
}
