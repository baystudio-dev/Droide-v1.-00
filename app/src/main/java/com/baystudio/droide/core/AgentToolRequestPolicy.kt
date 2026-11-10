package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Provider-contract boundary, independent of natural-language intent and permission grants. */
internal object AgentToolRequestPolicy {
    fun snapshot(request: JsonObject): Set<String> =
        (request["tools"] as? JsonArray)?.let(AgentRuntimePrompt::toolNames)?.toSet().orEmpty()

    fun denial(name: String, advertised: Set<String>): String? = if (name in advertised) null else
        "DENIED (request tool surface): tool '$name' was not advertised in this model request. No requested tool or tool lifecycle hook was executed."

    // Compatibility aliases are independently advertised schemas.

}
