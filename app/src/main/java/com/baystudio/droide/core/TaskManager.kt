package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

enum class TaskExecutionScope { AUTO, LOCAL, LOCAL_LINUX_ARM64 }

data class IdeTask(
    val label: String,
    val argv: List<String>,
    val cwd: File,
    val group: String = "task",
    val source: String = "auto",
    val executionScope: TaskExecutionScope = TaskExecutionScope.AUTO,
    val steps: List<List<String>> = listOf(argv),
    val environment: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val configurationError: String? = null,
)

data class TaskResult(
    val task: IdeTask,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val durationMs: Long,
)

// Process tasks use literal argv. Only an explicit VS Code shell task invokes Bash.


class TaskManager(
    private val root: File,
    private val terminals: TerminalManager? = null,
    private val activeFile: () -> String? = { null },
    private val taskExecutor: (suspend (IdeTask, Long) -> TaskResult)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    suspend fun list(): List<IdeTask> = withContext(Dispatchers.IO) {
        (loadCustom(".droide/tasks.json") + loadCustom(".vscode/tasks.json", vscode = true) + detect()).distinctBy { it.label }.take(100)
    }

    suspend fun runNamed(label: String, timeoutMs: Long = 30 * 60_000L): TaskResult {
        val task = list().singleOrNull { it.label == label } ?: error("Unknown project task: $label")
        return run(task, timeoutMs)
    }

    suspend fun run(task: IdeTask, timeoutMs: Long = 30 * 60_000L): TaskResult = withContext(Dispatchers.IO) {
        require(timeoutMs == 0L || timeoutMs in 1_000..86_400_000L) { "Invalid task timeout" }
        val tasks = (listOf(task) + list()).distinctBy { it.label }.associateBy { it.label }
        val ordered = TaskDependencyGraph.order(task.label, tasks)
        ordered.forEach(::validate)
        val started = System.nanoTime()
        // Pin one shared Ubuntu session for the entire graph. Changing tabs cannot redirect a dependency.
        val session = if (taskExecutor == null) terminals?.automatedExecutionSession(createIfMissing = true)
                ?: error(terminals?.automatedExecutionUnavailableReason() ?: "Local Linux backend is unavailable")
            else null
        val output = StringBuilder()
        var exit = 0
        var timedOut = false
        for (dependency in ordered) {
            currentCoroutineContext().ensureActive()
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            val remaining = if (timeoutMs == 0L) 0L else timeoutMs - elapsed
            if (timeoutMs != 0L && remaining < 1L) {
                exit = -1; timedOut = true
                output.append("[TASK TIMEOUT ${timeoutMs}ms before ${dependency.label}]\n")
                break
            }
            val result = taskExecutor?.invoke(dependency, remaining) ?: runSingle(dependency, requireNotNull(session), remaining)
            output.append("[TASK ${dependency.label}]\n${result.output}\n")
            if (output.length > 80_000) output.delete(0, output.length - 80_000)
            exit = result.exitCode; timedOut = result.timedOut
            if (exit != 0 || timedOut) break
        }
        TaskResult(task, exit, output.toString(), timedOut, (System.nanoTime() - started) / 1_000_000L)
    }

    private fun validate(task: IdeTask) {
        require(task.configurationError == null) { requireNotNull(task.configurationError) }
        require(task.executionScope != TaskExecutionScope.LOCAL) { "Project tasks require the Ubuntu workstation" }
        require((task.steps.isEmpty() && task.argv.isEmpty() && task.dependsOn.isNotEmpty()) || (task.steps.size in 1..32 && task.steps.all { step ->
            step.size in 1..128 && step.first().isNotBlank() && step.all { it.length <= 4_000 && '\u0000' !in it }
        })) { "Invalid task argv" }
        require(task.environment.size <= 128 && task.environment.all { (key, value) ->
            key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && value.length <= 4_000 && '\u0000' !in value
        }) { "Invalid task environment" }
        require(PathSecurity.contains(root, task.cwd) && task.cwd.isDirectory) { "Task cwd must stay inside workspace" }
    }

    private suspend fun runSingle(task: IdeTask, session: ITerminalSession, timeoutMs: Long): TaskResult {
        val started = System.nanoTime()
        val output = StringBuilder()
        var exec = ExecResult(0, "", false)
        for (step in task.steps) {
            currentCoroutineContext().ensureActive()
            val remaining = if (timeoutMs == 0L) 0L else timeoutMs - (System.nanoTime() - started) / 1_000_000L
            if (timeoutMs != 0L && remaining < 1L) { exec = ExecResult(-1, "Task deadline reached", true); break }
            if (session is DevicePtySessionHandle) session.refreshWorkspace()
            val backendShell = requireNotNull(terminals).commandShellFor(session)
            val argv = if (task.environment.isEmpty()) step else
                listOf("env") + task.environment.map { (key, value) -> "$key=$value" } + step
            exec = WorkspaceCommandInvocation.execute(session, root, task.cwd, argv, remaining, backendShell)
            output.append(exec.output).append('\n')
            if (output.length > 80_000) output.delete(0, output.length - 80_000)
            if (exec.exitCode != 0 || exec.timedOut) break
        }
        return TaskResult(
            task = task,
            exitCode = exec.exitCode,
            output = if (exec.timedOut) (output.toString() + "\n[TASK TIMEOUT ${timeoutMs}ms]").takeLast(80_000) else output.toString(),
            timedOut = exec.timedOut,
            durationMs = (System.nanoTime() - started) / 1_000_000L,
        )
    }

    private fun loadCustom(path: String, vscode: Boolean = false): List<IdeTask> {
        val config = runCatching { PathSecurity.resolveWithin(root, path) }.getOrNull() ?: return emptyList()
        if (!config.isFile || config.length() !in 1..256_000) return emptyList()
        val parsed = runCatching { WorkspaceJsonConfig.parseObject(config.readText()) }.getOrNull() ?: return emptyList()
        val array = parsed["tasks"] as? JsonArray ?: return emptyList()
        return array.take(100).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun string(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val label = string("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dependencies = TaskDependencyGraph.parseLabels(o["dependsOn"]) ?: return@mapNotNull null
            val argv = if (o["command"] == null && dependencies.isNotEmpty()) emptyList() else if (vscode && o["command"] is JsonPrimitive) {
                val command = string("command") ?: return@mapNotNull null
                val rawArgs = o["args"] as? JsonArray ?: if (o["args"] == null) JsonArray(emptyList()) else return@mapNotNull null
                val args = rawArgs.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
                if (args.any { it == null }) return@mapNotNull null
                when (string("type") ?: "process") {
                    "process" -> listOf(command) + args
                    "shell" -> {
                        if (command.contains("${'$'}{")) return@mapNotNull null
                        val expanded = args.filterNotNull().map { expandVariables(it) ?: return@mapNotNull null }
                        listOf("bash", "-c", command + expanded.joinToString("") { " " + shellQuote(it) })
                    }
                    else -> return@mapNotNull null
                }
            } else {
                val rawArgv = o["command"] as? JsonArray ?: return@mapNotNull null
                rawArgv.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
            }
            if ((argv.isEmpty() && dependencies.isEmpty()) || argv.size > 128) return@mapNotNull null
            if (argv.any { it == null || it.length > 4_000 || '\u0000' in it } || (argv.isNotEmpty() && argv.first().isNullOrBlank())) return@mapNotNull null
            val safeArgv = argv.filterNotNull().map { if (vscode && string("type") != "shell") expandVariables(it) ?: return@mapNotNull null else it }
            val options = o["options"] as? JsonObject ?: if (o["options"] == null) JsonObject(emptyMap()) else return@mapNotNull null
            if (o["cwd"] != null && string("cwd") == null) return@mapNotNull null
            val rawCwd = if (vscode) (options["cwd"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: if (options["cwd"] == null) "." else return@mapNotNull null
                else string("cwd") ?: "."
            val cwdRaw = expandVariables(rawCwd) ?: return@mapNotNull null
            val cwd = runCatching {
                val relative = if (File(cwdRaw).isAbsolute) {
                    require(PathSecurity.contains(root, File(cwdRaw))); File(cwdRaw).canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
                } else cwdRaw
                if (relative == "." || relative.isEmpty()) root.canonicalFile else PathSecurity.resolveWithin(root, relative)
            }.getOrNull() ?: return@mapNotNull null
            if (!cwd.isDirectory) return@mapNotNull null
            val group = string("group") ?: (o["group"] as? JsonObject)?.get("kind")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (o["group"] != null && group == null) return@mapNotNull null
            val background = (o["isBackground"] as? JsonPrimitive)?.booleanOrNull
            if (o["isBackground"] != null && background != false) return@mapNotNull null
            if (o["executionScope"] != null && string("executionScope") == null) return@mapNotNull null
            val executionScope = when (string("executionScope")?.trim()?.lowercase()) {
                null, "", "auto" -> TaskExecutionScope.AUTO
                "local" -> return@mapNotNull null
                "local-linux-arm64", "ubuntu" -> TaskExecutionScope.LOCAL_LINUX_ARM64
                else -> return@mapNotNull null
            }
            if (o["dependsOrder"] != null && string("dependsOrder") != "sequence") return@mapNotNull null
            val rawEnvironment = options["env"] as? JsonObject ?: if (options["env"] == null) JsonObject(emptyMap()) else return@mapNotNull null
            val environment = rawEnvironment.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                expandVariables(text) ?: return@mapNotNull null
            }
            IdeTask(label, safeArgv, cwd, group?.take(80) ?: "task", path, executionScope,
                steps = if (safeArgv.isEmpty()) emptyList() else listOf(safeArgv),
                environment = environment, dependsOn = dependencies).takeIf { runCatching { validate(it) }.isSuccess }
        }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun expandVariables(value: String): String? {
        val file = activeFile()?.takeIf { it.isNotBlank() }?.let { runCatching { PathSecurity.resolveWithin(root, it) }.getOrNull() }
        val variables = mutableMapOf("workspaceFolder" to root.canonicalPath, "workspaceFolderBasename" to root.name, "pathSeparator" to "/", "/" to "/")
        file?.let {
            variables += mapOf("file" to it.canonicalPath, "fileBasename" to it.name, "fileBasenameNoExtension" to it.name.let { name -> if (name.lastIndexOf('.') > 0) name.substring(0, name.lastIndexOf('.')) else name },
                "fileDirname" to it.parentFile.canonicalPath, "fileExtname" to if (it.name.lastIndexOf('.') > 0) it.name.substring(it.name.lastIndexOf('.')) else "",
                "relativeFile" to it.relativeTo(root.canonicalFile).invariantSeparatorsPath,
                "relativeFileDirname" to it.parentFile.relativeTo(root.canonicalFile).invariantSeparatorsPath.ifEmpty { "." })
        }
        var unresolved = false
        val expanded = Regex("\\$\\{([^}]+)}").replace(value) { match -> variables[match.groupValues[1]] ?: run { unresolved = true; match.value } }
        return expanded.takeUnless { unresolved || it.contains("${'$'}{") }
    }

    private fun metadataPath(path: String): File? = runCatching { PathSecurity.resolveWithin(root, path) }.getOrNull()

    private fun detect(): List<IdeTask> {
        val out = mutableListOf<IdeTask>()
        val gradlew = metadataPath("gradlew")
        if (gradlew?.isFile == true) {
            
            out += IdeTask("Gradle: test", listOf("sh", "./gradlew", "test"), root, "test")
            out += IdeTask("Gradle: assembleDebug", listOf("sh", "./gradlew", "assembleDebug"), root, "build")
        }

        val packageJson = metadataPath("package.json")
        if (packageJson?.isFile == true && packageJson.length() <= 2_000_000) {
            val scripts = runCatching {
                val obj = json.parseToJsonElement(packageJson.readText()) as? JsonObject
                (obj?.get("scripts") as? JsonObject)?.keys.orEmpty().take(40)
            }.getOrDefault(emptyList())
            scripts.forEach { script ->
                if (script.matches(Regex("[A-Za-z0-9:_-]{1,100}"))) {
                    out += IdeTask("npm: $script", listOf("npm", "run", script), root, if (script.contains("test", true)) "test" else "task")
                }
            }
        }

        if ((metadataPath("Cargo.toml")?.isFile == true)) {
            out += IdeTask("Cargo: check", listOf("cargo", "check"), root, "build")
            out += IdeTask("Cargo: test", listOf("cargo", "test"), root, "test")
        }
        if ((metadataPath("go.mod")?.isFile == true)) {
            out += IdeTask("Go: test ./...", listOf("go", "test", "./..."), root, "test")
            out += IdeTask("Go: build ./...", listOf("go", "build", "./..."), root, "build")
        }
        if ((metadataPath("pyproject.toml")?.isFile == true) || (metadataPath("pytest.ini")?.isFile == true) || (metadataPath("tests")?.isDirectory == true)) {
            val selected = runCatching { ProjectPythonConfiguration.load(root) }
            out += IdeTask("Pytest", selected.getOrNull()?.pytestArgv() ?: listOf("pytest"), root, "test",
                configurationError = selected.exceptionOrNull()?.message)
        }
        if ((metadataPath("Makefile")?.isFile == true) || (metadataPath("makefile")?.isFile == true)) {
            out += IdeTask("Make", listOf("make"), root, "build")
        }
        if (metadataPath("CMakeLists.txt")?.isFile == true && runCatching {
                SourceRunPreparation.checkedGeneratedPath(root, ".droide/build/cmake")
            }.isSuccess) {
            val configure = listOf("cmake", "-S", ".", "-B", ".droide/build/cmake", "-DCMAKE_BUILD_TYPE=Debug", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON")
            val build = listOf("cmake", "--build", ".droide/build/cmake", "--parallel", "2")
            out += IdeTask("CMake: configure", configure, root, "build")
            out += IdeTask("CMake: build", build, root, "build", dependsOn = listOf("CMake: configure"))
            out += IdeTask("CMake: test", listOf("ctest", "--test-dir", ".droide/build/cmake", "--output-on-failure"), root, "test", dependsOn = listOf("CMake: build"))
        }
        if (metadataPath("pom.xml")?.isFile == true) {
            val prefix = if (metadataPath("mvnw")?.isFile == true) listOf("sh", "./mvnw") else listOf("mvn")
            out += IdeTask("Maven: package", prefix + "package", root, "build")
            out += IdeTask("Maven: test", prefix + "test", root, "test")
        }
        if (root.listFiles()?.any { it.isFile && it.extension in setOf("csproj", "fsproj", "sln") && PathSecurity.contains(root, it) } == true) {
            out += IdeTask(".NET: build", listOf("dotnet", "build"), root, "build")
            out += IdeTask(".NET: test", listOf("dotnet", "test"), root, "test")
        }
        return out
    }
}
