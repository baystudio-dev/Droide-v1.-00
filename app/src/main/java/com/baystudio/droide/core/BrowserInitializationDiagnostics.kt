package com.baystudio.droide.core

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicLong

/**
 * A few logcat breadcrumbs per actual WebView creation, never per page or recomposition.
 * Native process crashes cannot be caught here: a missing phase completion is evidence
 * for a reproducer, not proof that the corresponding application call caused the crash.
 * No URL, page content, exception message, project path or credential is recorded.
 */
internal class BrowserInitializationDiagnostics(
    private val clock: () -> Long,
    private val emit: (String) -> Unit,
    private val environment: String = "",
) {
    enum class Phase { CREATE, CONFIGURE, ATTACH, MOUNT }
    data class Provider(val name: String?, val version: String?)

    private val id = nextId.incrementAndGet()

    fun <T> phase(phase: Phase, block: () -> T): T {
        val started = now()
        write("stage=${phase.name.lowercase()} event=begin $environment")
        try {
            return block().also { write("stage=${phase.name.lowercase()} event=complete elapsed_ms=${(now() - started).coerceAtLeast(0)}") }
        } catch (failure: Exception) {
            failed(phase, started, failure)
            throw failure
        } catch (failure: LinkageError) {
            failed(phase, started, failure)
            throw failure
        }
    }

    /** Call only after the application's normal constructor has returned successfully. */
    fun providerAfterCreation(read: () -> Provider?) {
        try {
            val provider = read()
            write("event=provider package=${token(provider?.name)} version=${token(provider?.version)}")
        } catch (_: Exception) {
            write("event=provider unavailable=true")
        } catch (_: LinkageError) {
            write("event=provider unavailable=true")
        }
    }

    private fun failed(phase: Phase, started: Long, failure: Throwable) {
        write("stage=${phase.name.lowercase()} event=failed elapsed_ms=${(now() - started).coerceAtLeast(0)} type=${token(failure.javaClass.simpleName)}")
    }

    private fun now(): Long = try { clock() } catch (_: Exception) { 0 } catch (_: LinkageError) { 0 }

    private fun write(message: String) {
        try { emit("surface=$id $message".trim().take(MAX_LINE_CHARACTERS)) }
        catch (_: Exception) { /* Diagnostics must not prevent normal browser operation. */ }
        catch (_: LinkageError) { /* A diagnostic backend may be unavailable independently. */ }
    }

    companion object {
        const val TAG = "DroideBrowserInit"
        internal const val MAX_LINE_CHARACTERS = 512
        private val nextId = AtomicLong()

        private fun token(value: String?): String = value.orEmpty().take(120)
            .map { if (it.isLetterOrDigit() || it in ".-_+") it else '_' }.joinToString("").ifBlank { "unknown" }

        fun create(): BrowserInitializationDiagnostics = BrowserInitializationDiagnostics(
            clock = SystemClock::elapsedRealtime,
            emit = { Log.i(TAG, it) },
            environment = "sdk=${Build.VERSION.SDK_INT} abi=${token(Build.SUPPORTED_ABIS.firstOrNull())}",
        )

        fun currentProvider(): Provider? = WebView.getCurrentWebViewPackage()?.let { Provider(it.packageName, it.versionName) }
    }
}
