package com.baystudio.droide.core

import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A user-shell observation, never an authorization or a replacement for executable validation. */
internal object UserToolchainEnvironment {
    const val MAX_CAPTURE_CHARS = 65_536
    const val MAX_VALUE_CHARS = 32_000
    private const val CWD_KEY = "__DROIDE_CAPTURE_CWD"
    val keys: List<String> = listOf(
        "PATH", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_NDK_HOME",
        "ANDROID_NDK_ROOT", "NDK_HOME", "GRADLE_USER_HOME", "CARGO_HOME", "RUSTUP_HOME",
        "GOPATH", "GOROOT", "FLUTTER_ROOT", "DOTNET_ROOT", "VIRTUAL_ENV", "CONDA_PREFIX",
    )

    fun nonce(): String = UUID.randomUUID().toString().replace("-", "")

    /** Preserve only defined directory variables across the legacy login-shell exec boundary. */
    fun exportDefinedLoginVariables(): String = keys.joinToString("; ") { key ->
        "if [ \"\${$key+x}\" = x ]; then export $key; fi"
    }

    /** A single admitted process chain: legacy login profile first, terminal Bash rc second. */
    fun captureArgv(script: String): List<String> = listOf(
        "/bin/sh", "-lc", exportDefinedLoginVariables() + "; exec /usr/bin/env HOME=/root /bin/bash --noprofile --rcfile /root/.bashrc -ic \"\$1\"",
        "droide-user-environment", script,
    )

    /** If observation failed, retain the exact old Android login-probe behavior. */
    fun probeArgv(userEnvironmentLoaded: Boolean, shell: String, script: String): List<String> =
        if (userEnvironmentLoaded) listOf(shell, "-c", script) else listOf("/bin/sh", "-lc", script)

    /** Print only directory-valued toolchain variables, not the whole (possibly secret) environment. */
    fun script(nonce: String): String {
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val fields = keys.joinToString(" ") { key -> "\"$key=\${$key-}\"" }
        return "builtin printf '%s\\0' 'droide-env-begin-$nonce' \"$CWD_KEY=\$(builtin pwd -P)\" $fields 'droide-env-end-$nonce'"
    }

    fun parse(output: String, nonce: String, guestCwd: String): Map<String, String> {
        require(output.length <= MAX_CAPTURE_CHARS) { "Shell environment output is too large" }
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        require(guestCwd.startsWith('/') && guestCwd.none { it == '\u0000' || it == '\n' || it == '\r' })
        val begin = "droide-env-begin-$nonce\u0000"
        val end = "droide-env-end-$nonce\u0000"
        val start = output.indexOf(begin)
        require(start >= 0 && output.indexOf(begin, start + begin.length) < 0) { "Shell environment frame is missing or ambiguous" }
        val finish = output.indexOf(end, start + begin.length)
        require(finish >= 0 && output.indexOf(end, finish + end.length) < 0) { "Shell environment frame is incomplete or ambiguous" }
        val records = output.substring(start + begin.length, finish).split('\u0000')
        require(records.lastOrNull() == "") { "Shell environment record is incomplete" }
        val values = linkedMapOf<String, String>()
        records.dropLast(1).forEach { record ->
            val at = record.indexOf('=')
            require(at > 0) { "Shell environment record is invalid" }
            val key = record.substring(0, at)
            val value = record.substring(at + 1)
            require((key in keys || key == CWD_KEY) && key !in values) { "Shell environment key is unexpected or duplicated" }
            require(value.length <= MAX_VALUE_CHARS && value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Shell environment value is invalid" }
            values[key] = value
        }
        require(values.keys == keys.toSet() + CWD_KEY) { "Shell environment keys are incomplete" }
        val observedCwd = values.remove(CWD_KEY)!!
        require(observedCwd.startsWith('/')) { "Shell environment cwd is invalid" }
        return values.filterValues { it.isNotEmpty() }.mapValues { (key, value) ->
            if (key == "PATH" || key == "GOPATH") {
                value.split(':').map { part ->
                    // Pin relative user paths to the observed guest cwd. Never let a later
                    // launch in another directory silently reinterpret the same snapshot.
                    Paths.get(observedCwd).resolve(part.ifEmpty { "." }).normalize().toString()
                }.distinct().joinToString(":")
            } else {
                require(value.startsWith('/')) { "Toolchain home must be absolute" }
                value
            }
        }.also { require(it.values.all { value -> value.length <= MAX_VALUE_CHARS }) }
    }

    /** Explicit managed selections win, while implicit defaults must not hide user tools. */
    fun merge(observed: Map<String, String>, base: Map<String, String>, protectedKeys: Set<String>,
        preferredBins: List<String>): Map<String, String> {
        require(observed.keys.all { it in keys })
        val merged = observed.filterKeys { it !in protectedKeys }.toMutableMap()
        if ("PATH" in merged) {
            merged["PATH"] = (preferredBins + merged.getValue("PATH").split(':') + base["PATH"].orEmpty().split(':'))
                .filter { it.isNotEmpty() }.distinct().joinToString(":")
        }
        require(merged.values.all { it.length <= MAX_VALUE_CHARS && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' } })
        return merged.toMap()
    }
}

/** Coalesces lookup/start callers. Failures are cached too, so broken rc does not run per tool. */
internal class UserToolchainEnvironmentCache(
    private val timeoutMs: Long = 5_000L,
    private val maxEntries: Int = 8,
    private val maxAgeMs: Long = 30_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Fallback { TIMED_OUT, SHELL_FAILED, INVALID_OUTPUT, INVALIDATED }
    data class Capture(val output: String, val exitCode: Int, val timedOut: Boolean = false)
    data class Observation(val environment: Map<String, String>, val fallback: Fallback? = null)
    private val mutex = Mutex()
    private val lock = Any()
    private var generation = 0L
    private data class Entry(val observation: Observation, val createdAt: Long)
    private val cache = LinkedHashMap<String, Entry>()

    init { require(timeoutMs in 1..30_000 && maxEntries in 1..32 && maxAgeMs in 1..60_000) }

    fun invalidate() = synchronized(lock) { generation++; cache.clear() }

    suspend fun resolve(key: String, guestCwd: String, capture: suspend (String) -> Capture): Observation = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val epoch = synchronized(lock) {
            cache[key]?.let { entry ->
                val age = nanoTime() - entry.createdAt
                if (age >= 0 && age < maxAgeMs * 1_000_000L) return@withLock entry.observation
                cache.remove(key)
            }
            generation
        }
        val nonce = UserToolchainEnvironment.nonce()
        val observation = try {
            val result = withTimeoutOrNull(timeoutMs) { capture(UserToolchainEnvironment.script(nonce)) }
            when {
                result == null || result.timedOut -> Observation(emptyMap(), Fallback.TIMED_OUT)
                result.exitCode != 0 -> Observation(emptyMap(), Fallback.SHELL_FAILED)
                else -> try {
                    Observation(UserToolchainEnvironment.parse(result.output, nonce, guestCwd))
                } catch (_: IllegalArgumentException) { Observation(emptyMap(), Fallback.INVALID_OUTPUT) }
            }
        } catch (failure: UserEnvironmentCleanupUnconfirmed) {
            // Parent cancellation remains cancellation; our own timeout must not
            // disguise unknown process cleanup as an ordinary fallback.
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) {
                cancelled.addSuppressed(failure)
                throw cancelled
            }
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Error text may contain shell output or private paths. Keep only a typed reason.
            Observation(emptyMap(), Fallback.SHELL_FAILED)
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (epoch != generation) Observation(emptyMap(), Fallback.INVALIDATED)
            else {
                while (cache.size >= maxEntries) cache.remove(cache.keys.first())
                cache[key] = Entry(observation, nanoTime())
                observation
            }
        }
    }
}
