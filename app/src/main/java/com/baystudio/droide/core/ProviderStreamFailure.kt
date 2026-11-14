package com.baystudio.droide.core

import kotlinx.serialization.json.*

enum class ProviderStreamFailureKind { QUOTA, RATE_LIMIT, TEMPORARY_SERVICE, AUTHENTICATION, INVALID_REQUEST, UNKNOWN }

/** A failed HTTP-200 generation is deliberately distinct from an HTTP rejection.
 * It must never enter ProviderRetryPolicy or certify partially received tool calls. */
class LlmProviderStreamException internal constructor(
    val kind: ProviderStreamFailureKind,
    val logicalStatus: Int?,
    val httpStatus: Int?,
    val responseBody: String,
    message: String,
    internal val publicSnapshot: ProviderFailureSnapshot? = null,
) : IllegalStateException(message) {
    fun isQuotaFailure() = kind == ProviderStreamFailureKind.QUOTA
}

/** Safe message before the request boundary has the actual resolved credentials.
 * Retain only error fields, never a failed response's partial native output. */
internal class ProviderErrorPayloadException(error: JsonElement) : IllegalStateException("Provider returned an error payload") {
    val payload: JsonElement = if (error is JsonObject) JsonObject(error.filterKeys {
        it in setOf("type", "code", "status", "message", "details", "param", "error_type")
    } + ((error["metadata"] as? JsonObject)?.let { metadata ->
        mapOf("metadata" to JsonObject(metadata.filterKeys { it in setOf("limit_source", "reason") }))
    } ?: emptyMap())) else error
}

internal object ProviderStreamFailure {
    fun fromPayload(error: ProviderErrorPayloadException, secrets: Collection<String>, httpStatus: Int? = 200, snapshot: ProviderFailureSnapshot? = null): LlmProviderStreamException {
        val body = buildJsonObject { put("error", error.payload) }.toString()
        // Redact the whole error before presentation bounds: slicing first could expose
        // an unrecognized prefix of a resolved OAuth token or escaped credential.
        val safe = ProviderFailure.redact(body, secrets)
        val status = logicalStatus(error.payload as? JsonObject)
        val limit = ProviderLimitPolicy.diagnose(status ?: 0, safe)
        val kind = when {
            ProviderLimitPolicy.accessStatus(status ?: 0, safe) != null -> ProviderStreamFailureKind.AUTHENTICATION
            limit?.blocksImmediateRetry == true -> ProviderStreamFailureKind.QUOTA
            status == 429 -> ProviderStreamFailureKind.RATE_LIMIT
            status in setOf(500, 502, 503, 504, 529) -> ProviderStreamFailureKind.TEMPORARY_SERVICE
            status in setOf(401, 403) -> ProviderStreamFailureKind.AUTHENTICATION
            status in setOf(400, 404, 413, 422) -> ProviderStreamFailureKind.INVALID_REQUEST
            else -> ProviderStreamFailureKind.UNKNOWN
        }
        val recovery = when (kind) {
            ProviderStreamFailureKind.QUOTA -> checkNotNull(limit).hint
            ProviderStreamFailureKind.RATE_LIMIT -> limit?.hint ?: ProviderLimitDiagnosis.REQUEST_LIMIT.hint
            ProviderStreamFailureKind.TEMPORARY_SERVICE -> "Provider service interrupted the response. Continue this chat when the service recovers."
            ProviderStreamFailureKind.AUTHENTICATION -> "Provider denied authentication or access. Check the credential and model permissions."
            ProviderStreamFailureKind.INVALID_REQUEST -> "Provider rejected the generation. Check model capabilities and request parameters."
            ProviderStreamFailureKind.UNKNOWN -> "Provider interrupted the response. Check the provider error before continuing."
        }
        val detail = ProviderFailure.describe(status ?: 0, safe).substringAfter('\n', "").take(500)
        val transport = httpStatus?.let { " inside HTTP $it" }.orEmpty()
        val message = "Provider response error$transport. $recovery " +
            "The incomplete response was not replayed; partial tool calls were not executed." +
            if (detail.isBlank()) "" else "\n$detail"
        return LlmProviderStreamException(kind, status, httpStatus, safe.take(16_384), message, snapshot)
    }

    fun fromUnsafeMessage(message: String, secrets: Collection<String>, httpStatus: Int?, snapshot: ProviderFailureSnapshot? = null): LlmProviderStreamException {
        val safe = ProviderFailure.redact(message, secrets)
        val transport = httpStatus?.let { " (HTTP $it)" }.orEmpty()
        return LlmProviderStreamException(ProviderStreamFailureKind.UNKNOWN, null, httpStatus, safe.take(16_384),
            "Provider response could not be read safely$transport. The incomplete response was not replayed; partial tool calls were not executed.\n${safe.take(500)}", snapshot)
    }

    private fun logicalStatus(error: JsonObject?): Int? {
        if (error == null) return null
        val labels = listOf("code", "type", "status", "error_type").mapNotNull { (error[it] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() }
        if (labels.any { it in setOf("authentication_error", "unauthenticated", "invalid_api_key") }) return 401
        if (labels.any { it in setOf("permission_error", "permission_denied") }) return 403
        listOf("code", "status").forEach { key ->
            (error[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 400..599 }?.let { return it }
        }
        return labels.firstNotNullOfOrNull { label -> when (label) {
            "insufficient_quota", "rate_limit_error", "rate_limit_exceeded", "resource_exhausted", "too_many_requests" -> 429
            "billing_error", "billing_not_active", "credit_balance_exhausted", "insufficient_credits", "organization_usage_limit_exceeded", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "billing_hard_limit_reached" -> 402
            "overloaded_error" -> 529
            "unavailable", "service_unavailable", "service_unavailable_error" -> 503
            "api_error", "server_error", "internal", "internal_error", "model_error" -> 500
            "timeout_error", "deadline_exceeded" -> 504
            "authentication_error", "unauthenticated", "invalid_api_key" -> 401
            "permission_error", "permission_denied" -> 403
            "invalid_request_error", "invalid_argument" -> 400
            "not_found_error", "not_found" -> 404
            "request_too_large" -> 413
            else -> null
        } }
    }
}
