package com.baystudio.droide.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static caller-wiring guard; controlled timing behavior is covered by the Service owner's sources. */
class AgentSendAdmissionSourceContractTest {
    @Test fun humanSendCapturesOnceBeforeAsyncPreflightAndForwardsThatSameValue() {
        val relative = "src/main/java/com/baystudio/droide/ui/AgentPane.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        val send = source.substringAfter("    fun send() {").substringBefore("    val physicalLandscape =")
        val capture = send.indexOf("val requestAdmittedAtNanos = System.nanoTime()")
        val launch = send.indexOf("uiState.scope.launchUiCatching(")
        val preflight = send.indexOf("localLlamaModels.resolveAgentConfig(")
        val submit = send.indexOf("agent.submitAsync(")
        val forwarded = send.indexOf("requestAdmittedAtNanos = requestAdmittedAtNanos,")
        assertTrue("Capture must precede asynchronous work", capture >= 0 && capture < launch)
        assertTrue("Configured-model wait remains after admission capture", launch < preflight && preflight < submit)
        assertTrue("Forward the original value into the Service", submit < forwarded)
        assertEquals("Do not resample a delayed Send", 1, Regex("System\\.nanoTime\\(\\)").findAll(send).count())
    }

    @Test fun paletteCommandCapturesBeforeBothAsyncPreflightsAndKeepsSubmissionDefaults() {
        val relative = "src/main/java/com/baystudio/droide/ui/IdeScreen.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        val palette = source
            .substringAfter("        if (showPalette) CommandPalette(files, commands, onPick = { cmd ->", "")
            .substringBefore("        }, onDismiss = { showPalette = false })")
        val fallback = palette.substringAfter("                else -> {", "")
        val capture = fallback.indexOf("val requestAdmittedAtNanos = System.nanoTime()")
        val launch = fallback.indexOf("launchUserAction {")
        val commandPreflight = fallback.indexOf("commands.resolve(")
        val modelPreflight = fallback.indexOf("localLlamaModels.resolveAgentConfig(")
        val submit = fallback.indexOf("agent.submitAsync(")
        val forwarded = fallback.indexOf("requestAdmittedAtNanos = requestAdmittedAtNanos,")
        assertTrue("Palette acceptance must be captured before asynchronous work", capture >= 0 && capture < launch)
        assertTrue("Command resolution must remain after original admission", launch < commandPreflight)
        assertTrue("Both preflights must precede submission", commandPreflight < modelPreflight && modelPreflight < submit)
        assertTrue("Forward the original palette value into the Service", submit < forwarded)
        assertEquals("Do not resample a delayed palette request", 1, Regex("System\\.nanoTime\\(\\)").findAll(fallback).count())
        assertTrue("Do not route through the late-sampling chat wrapper", !fallback.contains("agent.chatAsync("))
        val arguments = fallback.substringAfter("agent.submitAsync(").substringBefore(")").replace(Regex("\\s+"), "")
        assertEquals(
            "Keep the same scope, text and config with STEER and other submission defaults",
            "scope=scope,userText=resolved,config=config,requestAdmittedAtNanos=requestAdmittedAtNanos,",
            arguments,
        )
    }
}
