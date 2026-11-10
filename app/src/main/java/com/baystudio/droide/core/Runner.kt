package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** A backend maps a declared project cwd without changing the user's interactive shell. */
interface WorkspaceCommandSession {
    suspend fun execWorkspaceArgv(workspaceRoot: File, directory: File, command: List<String>, timeoutMs: Long): ExecResult
}

/** Bind an IDE invocation without changing the user's interactive shell directory. */
internal object WorkspaceCommandInvocation {
    fun argv(directory: File, command: List<String>, shell: String = "/bin/sh"): List<String> =
        argvAt(directory.canonicalPath, command, shell)

    fun remoteArgv(workspaceRoot: File, directory: File, remoteRoot: String,
                   command: List<String>, shell: String): List<String> {
        require(PathSecurity.contains(workspaceRoot, directory)) { "Command cwd escaped workspace" }
        val mapper = WorkspacePathMapper(workspaceRoot, remoteRoot)
        return argvAt(mapper.localToRemote(directory), command.map(mapper::toRemoteProtocolString), shell)
    }

    suspend fun execute(session: ITerminalSession, workspaceRoot: File, directory: File,
                        command: List<String>, timeoutMs: Long, fallbackShell: String = "/bin/sh"): ExecResult {
        require(PathSecurity.contains(workspaceRoot, directory)) { "Command cwd escaped workspace" }
        return if (session is WorkspaceCommandSession)
            session.execWorkspaceArgv(workspaceRoot, directory, command, timeoutMs)
        else session.execArgv(argv(directory, command, fallbackShell), timeoutMs)
    }

    private fun argvAt(directory: String, command: List<String>, shell: String): List<String> {
        require(command.isNotEmpty()) { "Workspace command is empty" }
        return listOf(
            shell, "-c", "cd -- \"${'$'}1\" && shift && exec \"${'$'}@\"",
            "droide-workspace-command", directory,
        ) + command
    }
}

// Filenames are passed as argv and never interpolated into a shell.
class Runner(
    private val terminal: ITerminalSession,
    private val workDir: File,
    private val contributedRunPlan: (String) -> RunPlan? = { null },
) {
    sealed interface FilePlanResolution {
        data class Ready(val plan: RunPlan) : FilePlanResolution
        data class Error(val message: String) : FilePlanResolution
    }

    fun resolveFilePlan(relPath: String): FilePlanResolution =
        resolveFilePlan(relPath, workDir, contributedRunPlan)

    companion object {
        /** IDE run/build jobs can be lengthy. 0 opts out of a process deadline. */
        const val DEFAULT_RUN_TIMEOUT_MS = 30L * 60_000L
        const val MAX_RUN_TIMEOUT_MS = 86_400_000L
        // Resolving a run plan must never require constructing a Local shell.
        fun resolveFilePlan(
            relPath: String,
            workDir: File,
            contributedRunPlan: (String) -> RunPlan? = { null },
        ): FilePlanResolution {
            val cliResult = DroideCli.handle(listOf("run", relPath), workDir)
            val resolved = if (cliResult is DroideCli.Result.Err && cliResult.contributedFallbackAllowed) {
                contributedRunPlan(relPath)?.let { DroideCli.Result.Run(it) } ?: cliResult
            } else cliResult
            return when (resolved) {
                is DroideCli.Result.Err -> FilePlanResolution.Error(resolved.msg)
                is DroideCli.Result.Open -> FilePlanResolution.Error("unexpected open result")
                is DroideCli.Result.Run -> FilePlanResolution.Ready(resolved.plan)
                is DroideCli.Result.Pkg -> FilePlanResolution.Error("package commands cannot be run as a file")
            }
        }
    }

    suspend fun runFile(relPath: String): String = withContext(Dispatchers.IO) {
        when (val resolved = resolveFilePlan(relPath)) {
            is FilePlanResolution.Error -> "Runner: ${resolved.message}"
            is FilePlanResolution.Ready -> executePlan(resolved.plan, DEFAULT_RUN_TIMEOUT_MS).output
        }
    }

    suspend fun runPlan(plan: RunPlan, timeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS): String = runPlanResult(plan, timeoutMs).output

    internal suspend fun runPlanResult(plan: RunPlan, timeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS): RunnerExecutionResult = withContext(Dispatchers.IO) {
        require(timeoutMs == 0L || timeoutMs in 1_000L..MAX_RUN_TIMEOUT_MS) { "Invalid tool timeout" }
        executePlan(plan, timeoutMs)
    }

    private suspend fun executePlan(plan: RunPlan, timeoutMs: Long): RunnerExecutionResult =
        ExportWriterAdmission.withSuspendingWriter(workDir, "Source runner") {
        plan.preparation?.let { preparation ->
            SourceRunPreparation.withRunDirectory(workDir, preparation.directory) { executePreparedPlan(plan, timeoutMs) }
        } ?: executePreparedPlan(plan, timeoutMs)
    }

    private suspend fun executePreparedPlan(plan: RunPlan, timeoutMs: Long): RunnerExecutionResult {
        if (plan.steps.isEmpty() || plan.steps.any { it.isEmpty() }) {
            return RunnerExecutionResult("Runner: run plan contains no executable command")
        }
        val generatedFiles = try {
            val runDirectory = SourceRunPreparation.checkedGeneratedPath(workDir, ".droide/run")
            check(runDirectory.mkdirs() || runDirectory.isDirectory) { "Could not prepare run directory" }
            plan.preparation?.let { SourceRunPreparation.prepare(workDir, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return RunnerExecutionResult("Runner: could not prepare source execution: ${error.message?.take(500)}")
        }
        if (terminal is DevicePtySessionHandle) {
            terminal.refreshWorkspace()
            val preparation = plan.preparation
            val commands = if (preparation != null) SourceRunPreparation.remotePreparationCommands(preparation, requireNotNull(generatedFiles))
                else listOf(listOf("mkdir", "-p", ".droide/run"))
            for (command in commands) {
                val prepare = terminal.execArgv(command, timeoutMs = timeoutMs)
                if (prepare.exitCode != 0 || prepare.timedOut) {
                    return RunnerExecutionResult("Runner: could not prepare remote run directory\n${prepare.output.take(8_000)}", prepare.exitCode, prepare.timedOut)
                }
            }
        }
        val out = StringBuilder("▶ ${plan.display}\n")
        var lastExit: Int? = null
        var timedOut = false
        for (originalStep in plan.steps) {
            val step = if (plan.pythonConfigurationIdentity != null) originalStep else resolveCompilerAlias(originalStep)
            val r = WorkspaceCommandInvocation.execute(terminal, workDir, workDir, step, timeoutMs)
            lastExit = r.exitCode
            timedOut = r.timedOut
            out.append("exit=${r.exitCode}\n${r.output.take(8_000)}\n")
            if (r.exitCode != 0 || r.timedOut) {
                if (r.exitCode in setOf(-1, 126, 127)) {
                    if (plan.pythonConfigurationIdentity?.startsWith("sha256:") == true) {
                        out.append("Check .droide/python.json and the selected interpreter inside Ubuntu. Droide did not create or replace the environment and will not fall back to system Python.\n")
                    }
                    out.append("Runtime/tool is unavailable in the selected execution environment. Install the tool in the Linux terminal or use a supported Extensions installer.\n")
                }
                if (r.timedOut) out.append("Tool execution timed out after ${timeoutMs}ms.\n")
                break
            }
        }
        return RunnerExecutionResult(out.toString().take(16_000), lastExit, timedOut, processStarted = true)
    }

    private suspend fun resolveCompilerAlias(step: List<String>): List<String> {
        if (step.isEmpty()) return step
        val alternative = when (step.first()) {
            "gcc" -> "clang"
            "g++" -> "clang++"
            else -> return step
        }
        val available = WorkspaceCommandInvocation.execute(terminal, workDir, workDir, listOf("sh", "-c", "command -v \"${'$'}1\" >/dev/null", "droide-compiler", step.first()), timeoutMs = 5_000)
        if (available.exitCode != 1 || available.timedOut) return step
        val fallback = WorkspaceCommandInvocation.execute(terminal, workDir, workDir, listOf("sh", "-c", "command -v \"${'$'}1\" >/dev/null", "droide-compiler", alternative), timeoutMs = 5_000)
        return if (fallback.exitCode == 0 && !fallback.timedOut) listOf(alternative) + step.drop(1) else step
    }

}
