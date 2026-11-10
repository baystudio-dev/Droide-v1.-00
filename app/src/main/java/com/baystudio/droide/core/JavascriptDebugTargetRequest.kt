package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal data class JavascriptDebugTargetRequest(val id: String, val request: String, val configuration: JsonObject) {
    companion object {
        fun parse(arguments: JsonElement?): JavascriptDebugTargetRequest {
            val args = arguments as? JsonObject ?: error("Invalid child debug request")
            require(args.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Child debug request exceeds its limit" }
            val configuration = args["configuration"] as? JsonObject ?: error("Missing child debug configuration")
            fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val request = string(args["request"])
            val declaredRequest = configuration["request"]
            val id = string(configuration["__pendingTargetId"])
            require(request in setOf("launch", "attach") && string(configuration["type"]) in setOf("pwa-node", "node") &&
                id != null && id.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                (declaredRequest == null || string(declaredRequest) == request)) { "Unsafe or unsupported child debug configuration" }
            return JavascriptDebugTargetRequest(id, requireNotNull(request),
                JsonObject(configuration + ("request" to JsonPrimitive(request))))
        }
    }
}
