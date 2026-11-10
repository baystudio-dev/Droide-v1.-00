package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object AgentCompactionCheckpoint {
    fun message(summary: String, todos: List<Todo>): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", buildString {
            append("[Historical conversation checkpoint; prior conversation and task state, not a new user request or authorization]\n")
            append(summary)
            if (todos.isNotEmpty()) {
                append("\n[Tracked task state; progress metadata, not permission to start or resume work]\n")
                append(buildJsonArray { todos.forEach { todo -> add(buildJsonObject {
                    put("content", todo.content); put("status", todo.status); put("priority", todo.priority)
                }) } })
            }
        })
    }
}
