package com.baystudio.droide.core

internal data class PythonToolInstallRecipe(val command: String, val healthCheckCommand: String)

/** The caller supplies the existing install lock. */

internal object PythonToolInstallCommands {
    fun recipe(
        familyId: String,
        packageSpec: String,
        command: String,
        healthArgs: List<String>,
        module: String? = null,
    ): PythonToolInstallRecipe {
        require(familyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) { "Invalid Python tool family" }
        require(command.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) { "Invalid Python tool command" }
        require(packageSpec.isNotBlank() && packageSpec.none { it.isISOControl() }) { "Invalid Python package request" }
        require(healthArgs.all { arg -> arg.none { it.isISOControl() } }) { "Invalid Python health argument" }
        require(module == null || module.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "Invalid Python tool module" }

        val paths = "tool_env=\"\$HOME/.local/share/droide-tools/\"${q(familyId)}; " +
            "tool_launcher=\"\$HOME/.local/bin/\"${q(command)}; "
        // HOME is caller-owned. Descendant entries used for app installs may not redirect writes.
        val verifyParents = "for tool_parent in \"\$HOME/.local\" \"\$HOME/.local/share\" " +
            "\"\$HOME/.local/share/droide-tools\" \"\$HOME/.local/bin\"; do " +
            "if [ -L \"\$tool_parent\" ] || { [ -e \"\$tool_parent\" ] && [ ! -d \"\$tool_parent\" ]; }; then " +
            "printf 'Python tool parent is aliased or is not a directory: %s; inspect it in Terminal before retrying.\\n' \"\$tool_parent\" >&2; exit 1; fi; done; "
        val layout = "[ ! -L \"\$tool_env\" ] && [ -d \"\$tool_env\" ] && " +
            "[ -f \"\$tool_env/pyvenv.cfg\" ] && [ ! -L \"\$tool_env/pyvenv.cfg\" ] && [ -x \"\$tool_env/bin/python\" ] || " +
            "{ printf 'Python tool environment is incomplete or aliased: %s; inspect it in Terminal before retrying.\\n' \"\$tool_env\" >&2; exit 1; }; "
        val identity = "import os, sys; sys.exit(0 if sys.prefix != sys.base_prefix and " +
            "os.path.realpath(sys.prefix) == os.path.realpath(sys.argv[1]) else 1)"
        val verifyEnv = layout + "\"\$tool_env/bin/python\" -I -c ${q(identity)} \"\$tool_env\" || " +
            "{ tool_status=\$?; printf 'Python tool interpreter is not the expected virtualenv: %s; inspect it in Terminal before retrying.\\n' \"\$tool_env\" >&2; exit \"\$tool_status\"; }; " +
            "\"\$tool_env/bin/python\" -I -m pip --version || " +
            "{ tool_status=\$?; printf 'Python tool virtualenv has no working pip: %s; repair it in Terminal before retrying.\\n' \"\$tool_env\" >&2; exit \"\$tool_status\"; }; "
        val arguments = healthArgs.joinToString(" ", prefix = if (healthArgs.isEmpty()) "" else " ", transform = ::q)
        val verifyVersion = if ("==" in packageSpec) {
            val distribution = packageSpec.substringBefore("==").substringBefore('[')
            val requestedVersion = packageSpec.substringAfter("==")
            val probe = """
                import sys
                from importlib.metadata import PackageNotFoundError, version
                from pip._vendor.packaging.version import InvalidVersion, Version
                try:
                    installed = version(sys.argv[1])
                    matches = Version(installed) == Version(sys.argv[2])
                except (PackageNotFoundError, InvalidVersion) as error:
                    raise SystemExit('Cannot verify requested Python package version: ' + str(error))
                if not matches:
                    raise SystemExit('Python package version mismatch: requested ' + sys.argv[2] + ', found ' + installed)
            """.trimIndent()
            "\"\$tool_env/bin/python\" -I -c ${q(probe)} ${q(distribution)} ${q(requestedVersion)} || exit \$?; "
        } else ""
        val exactHealth = if (module == null) {
            "[ -x \"\$tool_env/bin/\"${q(command)} ] || { printf 'Installed Python tool executable is missing: %s\\n' \"\$tool_env/bin/\"${q(command)} >&2; exit 1; }; " +
                "\"\$tool_env/bin/\"${q(command)}$arguments"
        } else {
            "\"\$tool_env/bin/python\" -I -m ${q(module)}$arguments"
        }
        // Keep the generated runtime wrapper's historical environment and argv semantics. Only
        // installer/health Python invocations use isolated mode.
        val wrapper = module?.let {
            "printf '%s\\n' '#!/bin/sh' " + q("exec \"\$HOME/.local/share/droide-tools/$familyId/bin/python\" -m $it \"\$@\"")
        }
        val collision = "tool_launcher_allowed() { " +
            "if [ -L \"\$tool_launcher\" ]; then " +

            "tool_link=\$(readlink -n -- \"\$tool_launcher\" && printf '.') || return \$?; " +
            "[ \"\$tool_link\" = \"\$tool_env/bin/\"${q("$command.")} ] && return 0; " +
            "elif [ ! -e \"\$tool_launcher\" ]; then return 0; " +
            (wrapper?.let { "elif [ -f \"\$tool_launcher\" ] && $it | cmp -s - \"\$tool_launcher\"; then return 0; " } ?: "") +
            "fi; " +
            "printf 'Python tool launcher already exists and is not owned by Droide: %s; move it aside in Terminal before retrying.\\n' \"\$tool_launcher\" >&2; return 1; }; "
        val stage = if (wrapper == null) {

            "ln -sfT -- \"\$tool_env/bin/\"${q(command)} \"\$tool_launcher_tmp\""
        } else {
            "$wrapper > \"\$tool_launcher_tmp\" && chmod 755 \"\$tool_launcher_tmp\""
        }
        val install = "(" + paths + verifyParents + collision + "tool_launcher_allowed || exit \$?; " +
            "if [ ! -e \"\$tool_env\" ] && [ ! -L \"\$tool_env\" ]; then python3 -I -m venv \"\$tool_env\" || exit \$?; fi; " +
            verifyEnv +
            "\"\$tool_env/bin/python\" -I -m pip install --upgrade -- ${q(packageSpec)} || exit \$?; " +
            "\"\$tool_env/bin/python\" -I -m pip check || exit \$?; " +
            verifyVersion + exactHealth + " || exit \$?; " + verifyParents +
            "mkdir -p \"\$HOME/.local/bin\" || exit \$?; " +
            "tool_launcher_tmp=\$(mktemp \"\$HOME/.local/bin/.droide-python-$command.XXXXXX\") || exit \$?; " +
            "trap 'tool_status=\$?; rm -f -- \"\$tool_launcher_tmp\"; exit \"\$tool_status\"' 0; " +
            "trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM; " +
            stage + " || exit \$?; " + verifyParents + "tool_launcher_allowed || exit \$?; " +
            "mv -fT -- \"\$tool_launcher_tmp\" \"\$tool_launcher\" || exit \$?; trap - 0 HUP INT TERM)"
        val health = "(" + paths + verifyParents + verifyEnv + "\"\$tool_env/bin/python\" -I -m pip check || exit \$?; " + verifyVersion + exactHealth + ")"
        return PythonToolInstallRecipe(install, health)
    }

    private fun q(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
