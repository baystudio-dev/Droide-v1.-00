package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DroideCliTool(
    private val authority: UnifiedPackageAuthority,
    private val boundWorkspaceId: String? = null,
) {
    init {
        require(boundWorkspaceId == null || boundWorkspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) {
            "Invalid bound CLI workspace identity"
        }
    }
    
    suspend fun execute(args: List<String>): String {
        val result = executeResult(args)
        return when {
            result.exitCode == DroideCliExitCode.USAGE && result.stderr.contains("Usage:") -> result.stderr
            result.stderr.isNotBlank() -> "Error: ${result.stderr}"
            else -> result.stdout
        }
    }

    suspend fun executeResult(args: List<String>): DroideCliExecutionResult = withContext(Dispatchers.IO) {
        if (args.isEmpty() || args.size == 1 && args[0] in setOf("--help", "-h", "help")) return@withContext success(usage())
        if (args.size == 2 && args[1] in setOf("--help", "-h", "help") &&
            args[0] in setOf("pkg", "plugin", "runtime", "process", "file", "attachment")) {
            return@withContext success(when (args[0]) {
                "pkg", "plugin", "runtime" -> usagePkg(args[0]).stderr
                else -> usage()
            })
        }
        try {
            when (args[0]) {
                "pkg" -> handlePkg(args.drop(1))
                "plugin" -> handlePkg(args.drop(1), familyPrefix = "plugin.", namespace = "plugin")
                "runtime" -> handlePkg(args.drop(1), familyPrefix = "runtime.", namespace = "runtime")
                "process" -> handleProcess(args.drop(1))
                "file" -> handleFile(args.drop(1))
                "attachment" -> success(AcpAttachmentAccess.execute(boundWorkspaceId, args.drop(1)))
                "ide" -> ExternalIdeProtocol.cli(boundWorkspaceId, args.drop(1))
                "run" -> failure(DroideCliExitCode.USAGE,
                    "The terminal CLI has no file runner. Use IDE Run/ide run_file, or the installed runtime command (for example: python3 hello.py). Check/install the runtime through Extensions first.")
                else -> usageResult()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            failure(DroideCliExitCode.USAGE, error.message ?: "Invalid CLI arguments")
        } catch (error: IllegalStateException) {
            failure(DroideCliExitCode.TRANSACTION_FAILED, error.message ?: "Package operation failed")
        } catch (error: Exception) {
            failure(DroideCliExitCode.INTERNAL, error.message ?: "CLI operation failed")
        }
    }

    private suspend fun handleProcess(args: List<String>): DroideCliExecutionResult {
        require(args.isNotEmpty()) { "Usage: droide process [list|status session-id|stop session-id|port port-number]" }
        val expected = if (args[0] == "list") 1 else 2
        require(args.size == expected) { "Unexpected or missing arguments for process ${args[0]}" }
        require(args[0] in setOf("list", "status", "stop", "port")) { "Unknown process command" }
        val provider = OwnedWorkspaceDiagnosticsRegistry.provider(boundWorkspaceId)
            ?: return failure(DroideCliExitCode.UNAVAILABLE, "No owned process provider is attached to this workspace")
        return when (args[0]) {
            "list" -> success(provider.processes.list())
            "status" -> provider.processes.status(args[1])?.let(::success)
                ?: failure(DroideCliExitCode.NOT_FOUND, "Owned command session not found")
            "stop" -> provider.processes.stop(args[1])?.let(::success)
                ?: failure(DroideCliExitCode.NOT_FOUND, "Owned command session not found; no process was stopped")
            "port" -> {
                require(args[1].matches(Regex("[0-9]{1,5}"))) { "Expected a numeric port between 1 and 65535" }
                val result = LoopbackPortProbe.inspect(args[1].toInt())
                if (result.state == LoopbackPortState.UNAVAILABLE) failure(DroideCliExitCode.UNAVAILABLE, result.render())
                else success(result.render())
            }
            else -> error("unreachable")
        }
    }

    private fun handleFile(args: List<String>): DroideCliExecutionResult {
        require(args.size == 2 && args[0] == "permissions") { "Usage: droide file permissions workspace-relative-path" }
        val provider = OwnedWorkspaceDiagnosticsRegistry.provider(boundWorkspaceId)
            ?: return failure(DroideCliExitCode.UNAVAILABLE, "No file diagnostic provider is attached to this workspace")
        return try {
            success(WorkspaceScriptPermissions.inspect(provider.projectRoot, args[1]).render())
        } catch (_: java.nio.file.NoSuchFileException) {
            failure(DroideCliExitCode.NOT_FOUND, "Workspace file not found")
        } catch (_: java.nio.file.AccessDeniedException) {
            failure(DroideCliExitCode.UNAVAILABLE, "Workspace file permissions could not be inspected")
        }
    }

    private suspend fun handlePkg(
        args: List<String>,
        familyPrefix: String? = null,
        namespace: String = "pkg",
    ): DroideCliExecutionResult {
        if (args.isEmpty()) return usagePkg(namespace)
        val command = args[0]
        val expected = when (command) {
            "list", "status" -> 1
            "search", "info", "install", "upgrade", "downgrade", "health", "repair", "uninstall" -> 2
            "use" -> null
            else -> return failure(DroideCliExitCode.USAGE, "Unknown $namespace command: $command")
        }
        require(expected == null || args.size == expected) { "Unexpected or missing arguments for $namespace $command" }
        if (command in setOf("upgrade", "health")) scopedFamily(args[1], familyPrefix, namespace)
        if (command == "downgrade") {
            require('@' in args[1] && parseTarget(args[1], familyPrefix, namespace).second !in setOf("latest", "Latest official")) {
                "Downgrade requires an exact package@version"
            }
        }
        return when (command) {
            "status" -> {
                val state = authority.transactionState.value
                success(buildString {
                    appendLine("PACKAGE_TRANSACTION active=${state.active}")
                    if (state.active) {
                        appendLine("operation_id=${state.operationId}")
                        appendLine("stage=${state.stage ?: "admission"}")
                        appendLine("message=${state.message ?: "Awaiting installer ownership."}")
                        state.logs.forEach(::appendLine)
                    } else appendLine("No managed package transaction is active in this workspace.")
                    append("This is an observation, not proof that a requested package was installed. Use info/health to verify its receipt.")
                })
            }
            "search" -> {
                val result = authority.search(args[1]).filter { visibleInNamespace(it.familyId, familyPrefix) }
                success(result.joinToString("\n") { "${it.familyId} - ${it.name} [${it.installedVersion ?: "Not installed"}]" })
            }
            "info" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                val result = authority.info(family, version)
                    ?: return failure(DroideCliExitCode.NOT_FOUND, "Package not found: ${args[1]}")
                success("Family: ${result.familyId}\nVersion: ${result.version}\nState: ${result.state}\nDependencies: ${result.dependencies.joinToString(", ")}")
            }
            "install" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                managedToolchain("install", family, version) ?: transaction(authority.install(family, version))
            }
            "upgrade" -> {
                val family = scopedFamily(args[1], familyPrefix, namespace)
                managedToolchain("install", family, "latest") ?: transaction(authority.upgrade(family))
            }
            "downgrade" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                managedToolchain("install", family, version) ?: transaction(authority.downgrade(family, version))
            }
            "use" -> {
                require(args.size == 2 || args.size == 4 && args[2] == "--workspace" && args[3].isNotBlank()) {
                    "Usage: droide $namespace use family@version [--workspace workspace-id]"
                }
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                val explicitWorkspace = args.getOrNull(3)
                require(boundWorkspaceId == null || explicitWorkspace == null || explicitWorkspace == boundWorkspaceId) {
                    "This CLI endpoint is bound to workspace $boundWorkspaceId"
                }
                // A socket published inside a workspace-bound terminal defaults to that workspace.
                // The legacy in-process/unbound CLI keeps null = global for backward compatibility.
                val workspace = explicitWorkspace ?: boundWorkspaceId
                transaction(authority.activate(family, version, workspace))
            }
            "list" -> success(authority.list().filter { visibleInNamespace(it.familyId, familyPrefix) }
                .joinToString("\n") { "${it.familyId}@${it.version} (${it.state})" })
            "health" -> {
                val result = authority.health(scopedFamily(args[1], familyPrefix, namespace))
                val text = "Health for ${result.familyId}: ${if (result.healthy) "OK" else "FAILED"} - ${result.messages.joinToString(", ")}"
                if (result.healthy) success(text) else failure(DroideCliExitCode.UNHEALTHY, text)
            }
            "repair" -> {
                val target = args[1]
                val (family, version) = parseTarget(target, familyPrefix, namespace)
                managedToolchain("repair", family, version) ?:
                    transaction(if ('@' in target) authority.repairVersion(family, version) else authority.repair(family))
            }
            "uninstall" -> {
                val (family, version) = parseTarget(args[1], familyPrefix, namespace)
                transaction(authority.uninstall(family, version))
            }
            else -> error("unreachable")
        }
    }

    private suspend fun managedToolchain(operation: String, family: String, version: String): DroideCliExecutionResult? {
        // An unbound in-process CLI retains its package API. Workspace terminals use the
        // same permission/license workflow as chat; a disabled tool cannot fall through.
        if (boundWorkspaceId == null) return null
        if (ExternalIdeToolRegistry.binding(boundWorkspaceId) == null)
            return failure(DroideCliExitCode.UNAVAILABLE, "Workspace toolchain approval is unavailable; reopen the workspace before installing.")
        val args = kotlinx.serialization.json.buildJsonObject {
            put("operation", kotlinx.serialization.json.JsonPrimitive(operation))
            put("family", kotlinx.serialization.json.JsonPrimitive(family))
            put("version", kotlinx.serialization.json.JsonPrimitive(version))
        }
        return ExternalIdeProtocol.cli(boundWorkspaceId, listOf("call", "droide_toolchain", args.toString()))
    }

    private fun parseTarget(
        target: String,
        familyPrefix: String? = null,
        namespace: String = "pkg",
    ): Pair<String, String> {
        val parts = target.split("@", limit = 2)
        val family = scopedFamily(parts[0], familyPrefix, namespace)
        val version = parts.getOrNull(1) ?: "latest"
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}")) && version.isNotBlank()) { "Invalid package version" }
        return family to version
    }

    private fun scopedFamily(family: String, familyPrefix: String?, namespace: String): String {
        requireFamily(family)
        if (familyPrefix == null) return family
        val normalized = when {
            family.startsWith(familyPrefix) -> family
            '.' !in family -> familyPrefix + family
            else -> throw IllegalArgumentException("$namespace accepts only ${familyPrefix.removeSuffix(".")} package families")
        }
        require(visibleInNamespace(normalized, familyPrefix)) { "$namespace package family is internal or unavailable through this namespace" }
        return normalized
    }

    private fun visibleInNamespace(family: String, familyPrefix: String?): Boolean {
        if (familyPrefix == null) return true
        if (!family.startsWith(familyPrefix)) return false
        return familyPrefix != "runtime." || !family.startsWith("runtime.deps.")
    }

    private fun requireFamily(family: String) {
        require(family.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
    }

    private fun transaction(result: UnifiedPackageTransactionResult): DroideCliExecutionResult =
        if (result.success) success(result.message) else failure(DroideCliExitCode.TRANSACTION_FAILED, result.message)

    private fun success(text: String) = DroideCliExecutionResult(DroideCliExitCode.OK, stdout = text)
    private fun failure(code: Int, text: String) = DroideCliExecutionResult(code, stderr = text)
    private fun usageResult() = failure(DroideCliExitCode.USAGE, usage())
    private fun usagePkg(namespace: String = "pkg") = failure(
        DroideCliExitCode.USAGE,
        "Usage: droide $namespace [search|info|install|upgrade|downgrade|use|list|status|health|repair|uninstall]",
    )

    private fun usage(): String = """
        Droide CLI
        Usage:
          droide pkg [command]
          droide plugin [command]
          droide runtime [command]
          droide attachment [read lease attachment-id [offset]|save lease attachment-id project-relative-path]
          droide process [list|status session-id|stop session-id|port port-number]
          droide file permissions workspace-relative-path
          droide ide [tools|call tool-name JSON|mcp-config]
          droide --help
        Packages: search, info, install, upgrade, downgrade, use, list, status, health, repair, uninstall.
        list shows installed receipts; search discovers catalog entries; status observes the current transaction.
        Source execution: use IDE Run or an installed runtime command such as python3 hello.py.
    """.trimIndent()
}
