package com.baystudio.droide

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only. These do not instantiate MainActivity or execute Android/Compose. */
class ExitOpenRearmSourceR64Test {
    private fun activitySource(): String {
        val suffix = "src/main/java/com/baystudio/droide/MainActivity.kt"
        return checkNotNull(listOf(File(suffix), File("app/$suffix"), File("../app/$suffix"))
            .firstOrNull { it.isFile }) { "MainActivity source fixture is missing" }.readText()
    }

    @Test fun successfulOpenRearmsImmediatelyBeforeAnyDeferredExitObservation() {
        val source = activitySource()
        val open = source.indexOf("appOwner.openFromUser()")
        assertTrue("The explicit Open action must call the application owner", open >= 0)
        val catch = source.indexOf("catch (failure: Exception)", open)
        assertTrue("Open must retain its failure handler", catch > open)
        val successfulOpen = source.substring(open, catch)
        // Permit explanatory line comments, but no intervening launch/effect/suspension.
        assertTrue("Successful Open must synchronously rearm the completion flag",
            Regex("""appOwner\.openFromUser\(\)\s*(?://[^\n]*\n\s*)*finishWhenExitCompletes\s*=\s*true""")
                .containsMatchIn(successfulOpen))
    }

    @Test fun closedLaunchKeepsAnUnarmedInitialFlagUntilExplicitOpenOrActiveExit() {
        val source = activitySource()
        assertTrue(source.contains("mutableStateOf(appOwner.mayPresentWorkspace(this@MainActivity) && !exit.completed)"))
        assertTrue(source.contains("if (closeUi && appOwner.shouldCloseUserInterface(this@MainActivity)) finishAndRemoveTask()"))
        assertTrue(source.contains("if (exit.started && !exit.completed && appOwner.mayPresentWorkspace(this@MainActivity)) finishWhenExitCompletes = true"))
        assertTrue(source.contains("if (exit.completed && finishWhenExitCompletes) return@open"))
        assertTrue(source.contains("canOpen = (!exit.started || exit.completed) && !(exit.completed && finishWhenExitCompletes)"))
    }
}
