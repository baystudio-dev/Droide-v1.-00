package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Display limits must never shorten a policy resource or merge two session capabilities. */
internal object BrowserInteractionAuthority {
    private val operations = setOf("click", "type", "check", "key", "hover", "swipe", "drag", "dialog_respond")

    fun policyResource(url: String, operation: String, target: String): String {
        require(operation in operations) { "Unsupported browser interaction" }
        require(url.length <= 8_192) { "Browser URL exceeds 8192 characters" }
        require(target.length <= 20_000) { "Browser target exceeds 20000 characters" }
        require(url.none { it.code < 32 || it.code == 127 } && target.none { it.code < 32 || it.code == 127 }) {
            "Browser URL and target must not contain control characters"
        }
        // Keep existing full-resource policy rules meaningful without allowing the URL to impersonate
        // the URL/action/target separator. Never silently rewrite it.
        require(operations.none { "#$it:" in url }) {
            "Browser interaction URL contains a reserved action separator; remove that fragment and request fresh approval"
        }
        return "$url#$operation:$target"
    }

    fun sessionResource(document: String, url: String, operation: String, target: String,
        text: String?, submit: Boolean, dialogText: String?): String {
        // JSON framing avoids delimiter collisions. Include every argument that changes the effect,
        // without copying entered text into approval UI or grant storage.
        val identity = JsonArray(listOf(document, url, operation, target, text, submit.toString(), dialogText)
            .map { JsonPrimitive(it) }).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "browser-interaction-v2:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
