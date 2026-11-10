package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentTtyPolicyTest {
    @Test fun ttyDemandReturnsClearNonLaunchingError() {
        val message = AgentTtyPolicy.unsupportedReason(true)
        assertNotNull(message)
        assertTrue(message!!.contains("No command was launched"))
        assertTrue(message.contains("visible user terminal"))
        assertTrue(message.contains("pipes"))
    }

    @Test fun ordinaryPipeCommandRemainsAllowed() {
        assertNull(AgentTtyPolicy.unsupportedReason(false))
    }

    @Test fun modelGuidanceDifferentiatesPipeInputFromTrueTty() {
        val prompt = AgentSystemPrompt.TEXT
        assertTrue(prompt.contains("requires_tty=true"))
        assertTrue(prompt.contains("keep_stdin_open=true"))
        assertTrue(prompt.contains("not a TTY"))
        assertTrue(prompt.contains("visible terminal"))
        assertTrue(prompt.contains("tmux"))
        assertTrue(prompt.contains("No command was launched").not() ||
            AgentTtyPolicy.unsupportedReason(true)!!.contains("No command was launched"))
    }

    @Test fun bothAgentCommandSchemasExposeAnExplicitTtyRequirement() {
        for (name in listOf("run_command", "exec_command")) {
            val definition = AgentToolCatalog.definitions.single {
                it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name
            }.jsonObject
            val properties = definition.getValue("function").jsonObject
                .getValue("parameters").jsonObject
                .getValue("properties").jsonObject
            assertEquals(JsonPrimitive("boolean"),
                properties.getValue("requires_tty").jsonObject["type"])
        }
    }
}
