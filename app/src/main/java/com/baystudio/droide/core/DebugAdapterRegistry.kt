package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

 
data class DebugStopSnapshot(
    val generation: Long,
    val reason: String,
    val description: String?,
    val threadId: Int?,
    val hitBreakpointIds: List<Int>,
)

 
enum class DebugAdapterTransport {
     
    STDIO,

     
    LOOPBACK_TCP_SERVER,

    JAVASCRIPT_DEBUG_TCP_SERVER,

     
    AUTHENTICATED_REVERSE_TCP,
}

 
data class AuthenticatedReverseTcpContract(
    val connectArgument: String,
    val authTokenArgument: String,
    val authHeaderName: String = "Auth-Token",
) {
    fun validate() {
        require(connectArgument.matches(Regex("--[A-Za-z0-9][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP connect argument" }
        require(authTokenArgument.matches(Regex("--[A-Za-z0-9][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP auth-token argument" }
        require(authHeaderName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,79}"))) { "Invalid reverse-TCP auth header" }
        require(!authHeaderName.equals("Content-Length", ignoreCase = true)) { "Auth header must not shadow Content-Length" }
    }
}

// Built-in adapters are conservative: Droide never downloads a debugger implicitly.
object DebugAdapterRegistry {
    data class Spec(
        val id: String,
        val extensions: Set<String>,
        val commandCandidates: List<List<String>>,
        val defaultArguments: (String, File) -> JsonObject,
        val source: String = "built-in",
        val transport: DebugAdapterTransport = DebugAdapterTransport.STDIO,
        val reverseTcp: AuthenticatedReverseTcpContract? = null,
        val autoConfigure: Boolean = true,
        val configurationTypes: Set<String> = setOf(id),
        val nativeDebugger: Boolean = false,
    ) {
        fun validate() {
            reverseTcp?.validate()
            require((transport == DebugAdapterTransport.AUTHENTICATED_REVERSE_TCP) == (reverseTcp != null)) {
                "Authenticated reverse-TCP transport requires exactly one reviewed reverse-TCP contract"
            }
            require(configurationTypes.isNotEmpty() && configurationTypes.all { it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }) {
                "Invalid debug configuration type"
            }
        }
    }

    val builtIns = listOf(
        Spec(
            id = "javascript-js-debug",
            extensions = setOf("js", "mjs", "cjs"),
            commandCandidates = listOf(listOf("js-debug-adapter", "0", "127.0.0.1")),
            defaultArguments = { file, root -> buildJsonObject {
                put("type", "pwa-node")
                put("program", File(root, file).absolutePath)
                put("cwd", root.absolutePath)
                put("console", "internalConsole")
                put("autoAttachChildProcesses", true)
            } },
            source = "built-in:user-installed-js-debug",
            transport = DebugAdapterTransport.JAVASCRIPT_DEBUG_TCP_SERVER,
            configurationTypes = setOf("javascript-js-debug", "node", "pwa-node"),
        ),
        Spec(
            id = "python-debugpy",
            extensions = setOf("py", "pyi"),
            commandCandidates = listOf(
                listOf("python3", "-m", "debugpy.adapter"),
                listOf("python", "-m", "debugpy.adapter"),
            ),
            defaultArguments = { file, root -> buildJsonObject {
                put("program", File(root, file).absolutePath)
                put("cwd", root.absolutePath)
                put("justMyCode", true)
                put("console", "internalConsole")
            } },
            configurationTypes = setOf("python-debugpy", "python", "debugpy"),
        ),
        Spec(
            id = "go-delve",
            extensions = setOf("go"),
            commandCandidates = listOf(
                listOf("dlv", "dap", "--listen=127.0.0.1:0"),
            ),
            defaultArguments = { file, root -> buildJsonObject {
                put("mode", "debug")
                put("program", File(root, file).absolutePath)
                put("cwd", root.absolutePath)
                put("stopOnEntry", false)
            } },
            source = "built-in:managed-delve",
            transport = DebugAdapterTransport.LOOPBACK_TCP_SERVER,
            configurationTypes = setOf("go-delve", "go"),
            // Delve controls native processes with ptrace. An executable alone is
            // not evidence that it can trace a process already traced by PRoot.
            autoConfigure = false,
            nativeDebugger = true,
        ),
        Spec(
            id = "lldb-dap",
            extensions = setOf("c", "h", "cc", "cpp", "cxx", "hpp", "rs", "swift"),
            commandCandidates = listOf(listOf("lldb-dap"), listOf("lldb-dap-18")),
            defaultArguments = { _, root -> buildJsonObject { put("cwd", root.absolutePath) } },
            source = "built-in:user-installed-ubuntu-lldb-dap",
            configurationTypes = setOf("lldb-dap"),
            autoConfigure = false,
            nativeDebugger = true,
        ),
    )

    fun forPath(path: String, extra: List<Spec> = emptyList()): List<Spec> {
        val ext = path.substringAfterLast('.', "").lowercase()
        return (extra + builtIns).distinctBy(Spec::id).filter { it.autoConfigure && ext in it.extensions }
    }

    fun byId(id: String, extra: List<Spec> = emptyList()): Spec? =
        (extra + builtIns).distinctBy(Spec::id).singleOrNull { it.id == id }

    fun byConfigurationType(type: String, extra: List<Spec> = emptyList()): Spec? {
        val specs = (extra + builtIns).distinctBy(Spec::id)
        byId(type, extra)?.let { return it }
        // VS Code extension debugger contributions use source-qualified ids.
        // Ambiguous aliases must be configured with adapterId explicitly.
        return specs.singleOrNull { type in it.configurationTypes || it.id.substringAfterLast(':') == type }
    }

    const val NATIVE_PROOT_WARNING = "Native debugging requires process-control capabilities. PRoot already uses ptrace; GDB/LLDB/Delve launch or attach may fail on Android. Installing an adapter does not certify native debugging. Use an explicitly configured remote target when supported."
}
