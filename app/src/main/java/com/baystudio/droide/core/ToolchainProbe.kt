package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

/** A failed observation is not evidence that an installed tool disappeared. */
internal class ToolchainProbeFailed(operation: String, cause: Exception) : IllegalStateException(
    "$operation could not be checked: ${cause.message?.take(400) ?: cause.javaClass.simpleName}. " +
        "Tool installation is unconfirmed, not missing. Retry the check after resolving the process/environment error.", cause,
)

internal suspend fun <T> checkedToolchainProbe(operation: String, probe: suspend () -> T): T = try {
    probe()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ToolchainProbeFailed) {
    throw failure
} catch (failure: Exception) {
    throw ToolchainProbeFailed(operation, failure)
}

/** Do not publish an empty map when the command did not complete successfully. */
internal object ExternalToolchainDiscovery {
    suspend fun observe(commands: List<String>, run: suspend (String) -> ExecResult): Map<String, String> =
        checkedToolchainProbe("Ubuntu executable discovery") {
            val result = run(ToolDiscoveryBatch.command(commands))
            check(!result.timedOut) { "Executable probe timed out" }
            check(result.exitCode == 0) { "Executable probe exited ${result.exitCode}: ${result.output.takeLast(300)}" }

            val observed = result.output.lineSequence().mapNotNull { it.substringBefore('\t').takeIf { command -> '\t' in it && command in commands } }.toSet()
            check(observed == commands.toSet()) { "Executable probe returned an incomplete response" }
            ToolDiscoveryBatch.parse(result.output, commands)
        }
}

/** Try healthy alternatives; report failed observations only when none resolves. */
internal suspend fun resolveToolchainCommand(candidates: List<List<String>>, lookup: suspend (String) -> String?): List<String>? {
    var failure: ToolchainProbeFailed? = null
    for (candidate in candidates) {
        if (candidate.isEmpty()) continue
        try {
            val executable = checkedToolchainProbe("Executable ${candidate.first()}") { lookup(candidate.first()) } ?: continue
            return listOf(executable) + candidate.drop(1)
        } catch (error: ToolchainProbeFailed) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
    return null
}
