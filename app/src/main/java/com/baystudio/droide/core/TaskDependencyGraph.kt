package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object TaskDependencyGraph {
    fun parseLabels(value: JsonElement?): List<String>? {
        val values = when (value) {
            null -> return emptyList()
            is JsonPrimitive -> listOf(value)
            is JsonArray -> value.toList()
            else -> return null
        }
        if (values.size > 100) return null
        return values.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
                it.isNotBlank() && it.none { c -> c.code < 32 }
            } ?: return null
        }.distinct()
    }

    fun order(label: String, tasks: Map<String, IdeTask>): List<IdeTask> {
        require(tasks.size <= 100) { "Too many project tasks" }
        val visiting = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val result = mutableListOf<IdeTask>()
        fun visit(name: String) {
            if (name in completed) return
            require(visiting.add(name)) { "Cyclic task dependency: $name" }
            val task = tasks[name] ?: error("Missing task dependency: $name")
            require(task.dependsOn.size <= 100) { "Too many task dependencies" }
            task.dependsOn.forEach(::visit)
            visiting.remove(name); completed.add(name); result += task
        }
        visit(label)
        return result
    }
}
