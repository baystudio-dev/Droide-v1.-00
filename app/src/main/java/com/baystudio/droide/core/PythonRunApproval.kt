package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** An approval binds a literal built-in Python plan, including the absence of a selection. */
internal class PythonRunApproval private constructor(val plan: RunPlan) {
    fun matches(current: RunPlan): Boolean = current == plan

    fun permissionResource(requestResource: String): String = buildJsonObject {
        put("ide_request", requestResource)
        put("python_configuration", requireNotNull(plan.pythonConfigurationIdentity))
        put("cwd", "workspace-root")
        put("argv", buildJsonArray { plan.steps.forEach { step ->
            add(buildJsonArray { step.forEach { add(JsonPrimitive(it)) } })
        } })
    }.toString()

    val preview: String get() = "Run in the Ubuntu project directory.\n" +
        "Interpreter: ${plan.steps.first().first()}\n" +
        "Selection: ${plan.pythonConfigurationIdentity}\n" +
        "Literal argv: " + buildJsonArray { plan.steps.single().forEach { add(JsonPrimitive(it)) } } +
        "\nNo environment is created or activated by this action."

    companion object {
        fun capture(plan: RunPlan): PythonRunApproval? {
            if (plan.pythonConfigurationIdentity == null) return null
            require(plan.steps.size == 1 && plan.steps.single().size == 2 && plan.preparation == null) {
                "Invalid built-in Python run plan"
            }
            return PythonRunApproval(plan.copy(steps = plan.steps.map { it.toList() }))
        }
    }
}
