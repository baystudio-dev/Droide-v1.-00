package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Duplicate-key guard for a bounded event. */

internal object OwnedChatEventKeys {
    fun rejectDuplicates(payload: String, json: Json) {
        val containers = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < payload.length) {
            when (payload[index]) {
                '{' -> containers += hashSetOf<String>()
                '[' -> containers += null
                '}', ']' -> { require(containers.isNotEmpty()) { "Malformed event nesting" }; containers.removeAt(containers.lastIndex) }
                '"' -> {
                    val start = index++
                    var escaped = false
                    var closed = false
                    while (index < payload.length) {
                        val c = payload[index++]
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') { closed = true; break }
                    }
                    require(closed) { "Incomplete event string" }
                    var next = index
                    while (next < payload.length && payload[next] in " \t\r\n") next++
                    if (next < payload.length && payload[next] == ':') {
                        val keys = containers.lastOrNull() ?: error("Object key outside event object")
                        val key = json.parseToJsonElement(payload.substring(start, index)).jsonPrimitive.content
                        require(keys.add(key)) { "Duplicate provider event field" }
                    }
                    continue
                }
            }
            index++
        }
        require(containers.isEmpty()) { "Incomplete event nesting" }
    }
}
