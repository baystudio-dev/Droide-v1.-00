package com.baystudio.droide.core

import kotlinx.serialization.json.*

object BrowserActionProbe {
    @JvmStatic fun main(args: Array<String>) {
        generateSequence(::readlnOrNull).forEach { line ->
            val input = Json.parseToJsonElement(line).jsonObject
            val op = input.getValue("operation").jsonPrimitive.content
            val script = if (op == "snapshot") AgentBrowserSnapshot.script(count = 200, elementCount = 40)
                else AgentBrowserInteraction.script(op, input["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input["text"]?.jsonPrimitive?.contentOrNull, input["submit"]?.jsonPrimitive?.booleanOrNull ?: false,
                    input["checked"]?.jsonPrimitive?.booleanOrNull, input["option_index"]?.jsonPrimitive?.intOrNull)
            println(JsonPrimitive(script))
        }
    }
}
