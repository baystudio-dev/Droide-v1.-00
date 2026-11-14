package com.baystudio.droide.core

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process

/** A restart changes automatic restoration, never the project or saved recovery buffers. */
data class StartupRecoveryPolicy internal constructor(
    val deferEditorReopen: Boolean = false,
    // An abnormal exit is not evidence that all future PTYs are broken. Suppress
    // automatic process startup only; explicit terminal/Run requests may retry.
    val allowAutomaticTerminalStart: Boolean = true,
    val allowTerminalRevive: Boolean = true,
    internal val clearTerminalRevive: Boolean = false,
    // One immutable process decision, different for each newly observed abnormal exit.
    // A Boolean alone repeats on consecutive crash restarts and can match a saved
    // native editor/Web panel from the preceding recovery process.
    val recoveryGeneration: String? = null,
) {
    fun recoveredEditorPath(requested: String, available: List<String>): String = when {
        deferEditorReopen || requested.isBlank() -> ""
        requested in available -> requested
        else -> available.firstOrNull().orEmpty()
    }

    companion object {
        val NORMAL = StartupRecoveryPolicy()
        private const val PREFERENCES = "droide_startup_recovery"
        private const val LAST_CONSUMED_EXIT = "last_consumed_exit"
        @Volatile private var processPolicy: StartupRecoveryPolicy? = null

        /** Activity recreation, workspace changes and the terminal host share one process decision. */
        fun forProcess(context: Context): StartupRecoveryPolicy = processPolicy ?: synchronized(this) {
            processPolicy ?: run {
                val app = context.applicationContext
                val previousExit = readPreviousExit(app)
                // Recovery metadata is advisory. An unreadable or old wrong-type marker
                // must not crash before workspace initialization can show its error UI.
                val preferences = try {
                    app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                } catch (_: RuntimeException) { null }
                val consumed = readConsumedExitIdentity(preferences)
                val decision = decide(previousExit, consumed)
                // Publish first. A failed preference write must only repeat conservative recovery
                // on a later process; it cannot change the policy part way through this launch.
                processPolicy = decision
                if (previousExit != null && previousExit.identity != consumed) {
                    try { preferences?.edit()?.putString(LAST_CONSUMED_EXIT, previousExit.identity)?.apply() }
                    catch (_: RuntimeException) { /* A later launch can conservatively recover again. */ }
                }
                decision
            }
        }

        internal fun readConsumedExitIdentity(preferences: SharedPreferences?): String? = try {
            preferences?.getString(LAST_CONSUMED_EXIT, null)
        } catch (_: RuntimeException) { null }

        internal fun decide(previousExit: PreviousProcessExit?, consumedIdentity: String?): StartupRecoveryPolicy {
            if (previousExit == null || previousExit.timestamp <= 0 || previousExit.identity == consumedIdentity) return NORMAL
            return when (previousExit.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY -> StartupRecoveryPolicy(
                    deferEditorReopen = true,
                    allowAutomaticTerminalStart = false,
                    allowTerminalRevive = false,
                    recoveryGeneration = previousExit.identity,
                )
                ApplicationExitInfo.REASON_USER_REQUESTED -> StartupRecoveryPolicy(
                    allowTerminalRevive = false,
                    clearTerminalRevive = true,
                )
                else -> NORMAL
            }
        }

        private fun readPreviousExit(context: Context): PreviousProcessExit? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                val manager = context.getSystemService(ActivityManager::class.java) ?: return null
                val name = Application.getProcessName()
                val currentPid = Process.myPid()
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .filter { it.processName == name && it.pid != currentPid }
                    .maxByOrNull { it.timestamp }
                    ?.let { PreviousProcessExit(it.timestamp, it.pid, it.reason) }
            } catch (_: RuntimeException) {
                // Unavailable Android history is not proof of an abnormal exit.
                null
            }
        }
    }
}

/** No crash description, trace, command, file body or credential is persisted. */
internal data class PreviousProcessExit(val timestamp: Long, val pid: Int, val reason: Int) {
    val identity: String get() = "$timestamp:$pid:$reason"
}
