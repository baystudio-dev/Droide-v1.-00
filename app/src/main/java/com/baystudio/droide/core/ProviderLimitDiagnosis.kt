package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Provider-reported limit identity, never a measurement of the user's account balance. */
internal enum class ProviderLimitDiagnosis(val hint: String, val blocksImmediateRetry: Boolean) {
    CREDIT("Provider reports insufficient credit. Check the provider account; Droide has not measured its balance.", true),
    BILLING("Provider reports a billing setup problem. Check account activation and payment settings.", true),
    SPEND("Provider reports an account, project or workspace spending limit. Credit may still remain; check the configured budget.", true),
    ACCOUNT_QUOTA("Provider reports an account or project quota limit. Check quota and billing settings; this does not establish an empty credit balance.", true),
    PERIOD_QUOTA("Provider reports a daily or monthly usage quota. Check its reset and quota settings; this is separate from remaining credit.", true),
    IN_FLIGHT_BUDGET("Provider reports an in-flight request spending budget limit. Credit may remain; wait for other requests or check request cost.", true),
    REQUEST_BUDGET("Provider reports that this request exceeds its request budget. Check requested cost and budget; retrying unchanged may not help.", true),
    PAYMENT_REQUIRED("Provider requires a payment or account-budget adjustment. Inspect its error details; remaining credit is unknown.", true),
    REQUEST_LIMIT("Provider rate or quota limit reached. Wait before retrying or check request and token limits; remaining credit is unknown.", false),
}

internal object ProviderLimitPolicy {
    private val json = Json { ignoreUnknownKeys = true }
    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private val access = setOf("authentication_error", "unauthenticated", "invalid_api_key", "permission_error", "permission_denied")
    private val credit = setOf("credit_balance_exhausted", "insufficient_credits")
    private val billing = setOf("billing_error", "billing_not_active", "billing_not_enabled")
    private val spend = setOf("billing_hard_limit", "billing_hard_limit_reached", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "enforced_spend_limit_reached")
    private val account = setOf("insufficient_quota", "account_quota_exceeded", "organization_usage_limit_exceeded")
    private val request = setOf("rate_limit_error", "rate_limit_exceeded", "resource_exhausted", "too_many_requests")
    private val spendMessage = Regex("""\b(?:spend(?:ing)? (?:cap|limit)|billing (?:hard )?limit)(?: has been| is| was)? (?:reached|exceeded|exhausted)\b""")
    private val periodMessage = Regex("""\b(?:daily|monthly) (?:quota|limit|usage threshold)(?: has been| is| was)? (?:reached|exceeded|exhausted)\b|\b(?:reached|exceeded|exhausted) (?:your |the )?(?:daily|monthly) (?:quota|limit|usage threshold)\b|\b(?:quota|requests?|tokens?) (?:exceeded|exhausted) per (?:day|month)\b""")

    private val routerBudgets = setOf("openrouter_guardrail_budget", "openrouter_workspace_budget", "openrouter_key_limit")

    private data class Evidence(val codes: Set<String>, val message: String,
        val details: List<JsonObject>, val metadata: JsonObject?)

    private fun evidence(body: String): Evidence {
        val bounded = body.takeIf { it.length <= 65_536 }
        val root = bounded?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val error = root?.get("error") as? JsonObject
        val fields = error ?: root
        val codes = listOf("code", "type", "status", "error_type").mapNotNull { string(fields?.get(it))?.lowercase() }.toMutableSet()
        val detailsObject = fields?.get("details") as? JsonObject
        string(detailsObject?.get("error_code"))?.lowercase()?.let(codes::add)
        val details = (fields?.get("details") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        details.filter { string(it["@type"]) == "type.googleapis.com/google.rpc.ErrorInfo" }
            .mapNotNull { string(it["reason"])?.lowercase() }.forEach(codes::add)
        val plain = string(root?.get("error")) ?: bounded?.trim()?.takeIf { root == null && !it.startsWith("{") && !it.startsWith("[") }.orEmpty()
        val plainCode = plain.substringBefore(' ').substringBefore(':').lowercase()
        if (plainCode in credit + billing + spend + account + request + access) codes += plainCode
        return Evidence(codes, (string(fields?.get("message")) ?: plain).take(8192).lowercase(),
            details, fields?.get("metadata") as? JsonObject)
    }

    fun accessStatus(status: Int, body: String): Int? = accessStatus(status, evidence(body))
    private fun accessStatus(status: Int, evidence: Evidence): Int? {
        if (status == 401 || evidence.codes.any { it in setOf("authentication_error", "unauthenticated", "invalid_api_key") }) return 401
        if (evidence.codes.any { it in setOf("permission_error", "permission_denied") }) return 403
        if (status == 403 && string(evidence.metadata?.get("limit_source")) !in routerBudgets) return 403
        return null
    }

    fun diagnose(status: Int, body: String): ProviderLimitDiagnosis? {
        val evidence = evidence(body)
        val codes = evidence.codes
        val details = evidence.details
        if (accessStatus(status, evidence) != null) return null
        if (status !in setOf(0, 400, 402, 403, 429)) return null
        val metadata = evidence.metadata
        val source = string(metadata?.get("limit_source"))
        val reason = string(metadata?.get("reason"))
        if (source in routerBudgets) return ProviderLimitDiagnosis.SPEND
        if (status == 403) return null
        if (source == "openrouter_in_flight_budget" && reason == "in_flight_budget_exhausted") return ProviderLimitDiagnosis.IN_FLIGHT_BUDGET
        if (source == "openrouter_credits" && reason == "weight_exceeds_budget") return ProviderLimitDiagnosis.REQUEST_BUDGET
        if (codes.any { it in credit }) return ProviderLimitDiagnosis.CREDIT
        if (codes.any { it in billing }) return ProviderLimitDiagnosis.BILLING
        if (codes.any { it in spend }) return ProviderLimitDiagnosis.SPEND
        if (codes.any { it in account }) return ProviderLimitDiagnosis.ACCOUNT_QUOTA
        val message = evidence.message
        if (status in setOf(400, 402, 429) || codes.any { it in request }) {
            if (spendMessage.containsMatchIn(message)) return ProviderLimitDiagnosis.SPEND
            val quotaIds = details.flatMap { detail ->
                val violations = (detail["violations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                (listOf(detail) + violations).mapNotNull { string(it["quotaId"]) ?: string(it["quota_id"]) }
            }.map { it.lowercase().filter(Char::isLetterOrDigit) }
            if (periodMessage.containsMatchIn(message) || quotaIds.any { "perday" in it || "permonth" in it } ||
                ("quota exceeded" in message && Regex("\\b[a-z]*(?:requests|tokens)per(?:day|month)[a-z]*\\b").containsMatchIn(message))) return ProviderLimitDiagnosis.PERIOD_QUOTA
        }
        if (status == 402) return ProviderLimitDiagnosis.PAYMENT_REQUIRED
        if (status == 429 || codes.any { it in request }) return ProviderLimitDiagnosis.REQUEST_LIMIT
        return null
    }
}
