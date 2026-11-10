package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Presence, execution and requested identity are separate evidence, never interchangeable. */
data class UserToolCommandCheck(
    val command: String,
    val arguments: List<String>?,
    val expectedPath: String? = null,
    val miseRequest: String? = null,
    val requestedToolVersion: String? = null,
)

internal object UserToolUpdateHealth {
    data class Result(val summary: String, val identityVerified: Boolean)

    // Explicit non-interactive contracts. Unknown tools, including stdio servers, remain usable
    // through their providers without a guessed universal --version.
    private val knownArguments = mapOf(
        "node" to listOf("--version"), "npm" to listOf("--version"),
        "python" to listOf("--version"), "python3" to listOf("--version"),
        "kotlinc" to listOf("-version"), "java" to listOf("-version"),
        "javac" to listOf("-version"), "jar" to listOf("--version"),
        "go" to listOf("version"), "rustc" to listOf("--version"), "cargo" to listOf("--version"),
        "rustfmt" to listOf("--version"), "cargo-clippy" to listOf("--version"),
        "zig" to listOf("version"), "dart" to listOf("--version"),
        "swift" to listOf("--version"), "dotnet" to listOf("--version"),
        "clang" to listOf("--version"), "clang++" to listOf("--version"),
        "bun" to listOf("--version"), "uv" to listOf("--version"),
        "pnpm" to listOf("--version"), "yarn" to listOf("--version"),
        "bazel" to listOf("--version"), "pwsh" to listOf("--version"),
    )

    fun checks(familyId: String, requested: String, recipe: UserToolUpdateRecipe): List<UserToolCommandCheck> {
        val apt = UbuntuAptCatalog.find(familyId).takeIf { recipe.provider == "Ubuntu APT" }
        val vendor = LocalUbuntuVendorCatalog.find(familyId)?.takeUnless { it.lsp }
        return recipe.healthCommands.map { command ->
            require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Unsafe health command" }
            val arguments = when {
                apt != null && command in apt.commands && apt.launchArgs(command).isEmpty() -> apt.probeArgs(command)
                command in knownArguments -> knownArguments.getValue(command)
                vendor?.command == command -> vendor.healthArgs
                else -> null
            }
            UserToolCommandCheck(command, arguments, recipe.expectedCommandPaths[command], recipe.miseRequest,
                // Rust components have independent versions; never compare rustfmt with rustc.
                requested.takeIf { (recipe.miseRequest != null && familyId !in setOf("quality.rustfmt", "quality.clippy")) ||
                    (recipe.provider == "npm" && command in setOf("npm", "pnpm", "yarn")) })
        }
    }

    suspend fun verify(recipe: UserToolUpdateRecipe, execute: suspend (String) -> ExecResult): Result {
        val evidence = mutableListOf<String>()
        val pending = mutableListOf<String>()
        suspend fun run(command: String): String {
            currentCoroutineContext().ensureActive()
            val result = execute(command)
            currentCoroutineContext().ensureActive()
            check(!result.timedOut && !result.outputIncomplete && result.exitCode == 0) {
                "The package manager finished, but selected-tool verification failed. ${result.output.takeLast(2_000)}"
            }
            return result.output
        }
        suspend fun path(command: String): String = run(command).trim().also {
            check(it.startsWith('/') && it.length <= 4096 && it.none(Char::isISOControl)) {
                "The package manager finished, but it did not return a usable executable path."
            }
        }
        suspend fun canonical(value: String): String = path("readlink -f -- ${q(value)}")
        for (check in recipe.selectedCommandChecks) {
            require(check.command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Unsafe selected command" }
            require(check.arguments == null || (check.arguments.size <= 8 && check.arguments.all {
                it.length <= 120 && it.none(Char::isISOControl)
            })) { "Unsafe selected-command probe arguments" }
            val selected = path("p=\$(command -v ${q(check.command)}) || exit; case \"\$p\" in /*) ;; *) exit 1;; esac; test -x \"\$p\" && printf '%s\\n' \"\$p\"")
            val selectedCanonical = canonical(selected)
            val expected = when {
                check.expectedPath != null -> canonical(check.expectedPath)
                check.miseRequest != null -> canonical(path("mise which ${q(check.command)} --tool ${q(check.miseRequest)}"))
                recipe.provider == "npm" -> {
                    val prefix = path("npm prefix --global")
                    canonical("$prefix/bin/${check.command}")
                }
                else -> null
            }
            var actual = selectedCanonical
            if (expected != null && actual != expected && check.miseRequest != null) {
                // Only the configured shim location can delegate lookup to mise. A random shadow wrapper with
                // the same name/version is not the installed executable.
                val shim = path("printf '%s\\n' \"\${MISE_DATA_DIR:-\${XDG_DATA_HOME:-\$HOME/.local/share}/mise}/shims/${check.command}\"")
                if (selected == shim) actual = canonical(path("mise which ${q(check.command)}"))
            }
            check(expected == null || actual == expected) {
                "The package manager finished, but ${check.command} still selects $selected ($actual); the updated provider selects $expected. " +
                    "Review the project toolchain/PATH selection. Your selection was preserved."
            }
            if (check.arguments == null) {
                evidence += "${check.command}: $selected"
                pending += "${check.command} executable health/version"
                continue
            }
            val output = run((listOf(selected) + check.arguments).joinToString(" ", transform = ::q) + " 2>&1")
            val version = observedVersion(check.command, output)
            val requested = check.requestedToolVersion
            if (requested != null && requested.removePrefix("v").matches(Regex("[0-9]+(?:\\.[0-9]+){0,2}(?:[-+][0-9A-Za-z.-]+)?")) && version != null) {
                check(matchesNumericRequest(requested, version)) {
                    "The package manager finished, but selected ${check.command} reports $version instead of requested $requested at $selected. " +
                        "Review the project toolchain/PATH selection. Your selection was preserved."
                }
            }
            evidence += "${check.command}${version?.let { " $it" }.orEmpty()}: $selected ($actual)"
            if (expected == null || version == null) pending += "${check.command} requested version/origin"
            else if (recipe.provider == "Ubuntu APT") pending += "${check.command} upstream/package-version equivalence"
            else if (requested == null) pending += "${check.command} requested package/tool-version equivalence"
            else if (!requested.removePrefix("v").matches(Regex("[0-9]+(?:\\.[0-9]+){0,2}(?:[-+][0-9A-Za-z.-]+)?")))
                pending += "${check.command} upstream freshness for '$requested'"
        }
        if (recipe.selectedCommandChecks.isEmpty()) pending += "selected executable health/version"
        val detail = evidence.joinToString("; ")
        return if (pending.isEmpty()) Result("Selected executable path, execution and version checked. $detail", true)
        else Result("Provider completed; verification pending for ${pending.distinct().joinToString(", ")}. " +
            if (detail.isEmpty()) "Check the selected tool in Terminal." else "Observed: $detail", false)
    }

    // Parse a tool's own version banner, never an arbitrary dependency version substring.
    fun observedVersion(command: String, output: String): String? {
        val pattern = when (command) {
            "kotlinc" -> "^(?:info: )?kotlinc-jvm ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "node" -> "^v([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)$"
            "zig", "dotnet", "npm", "pnpm", "yarn", "bun" -> "^([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)$"
            "go" -> "^go version go([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "rustc", "cargo", "rustfmt", "uv", "bazel" -> "^${Regex.escape(command)} ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "python", "python3" -> "^Python ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "java" -> "^(?:openjdk|java) version \"([0-9][0-9A-Za-z._+-]*)\""
            "javac" -> "^javac ([0-9][0-9A-Za-z._+-]*)(?:\\s|$)"
            "dart" -> "^Dart SDK version: ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "swift" -> "^(?:Apple )?Swift version ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "clang", "clang++" -> "^(?:(?:Ubuntu|Debian|Apple) )?clang version ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            "pwsh" -> "^PowerShell ([0-9][0-9A-Za-z.+-]*)(?:\\s|$)"
            else -> return null
        }
        return Regex(pattern, RegexOption.MULTILINE).findAll(output.trim()).map { it.groupValues[1] }.toSet().singleOrNull()
    }

    fun matchesNumericRequest(requested: String, observed: String): Boolean {
        val value = requested.removePrefix("v")
        return if (value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) observed == value || observed.startsWith("$value.")
        else observed == value
    }
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
