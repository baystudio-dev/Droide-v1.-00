package com.baystudio.droide.core

/** Probe-only cwd/PATH isolation, not a filesystem/network sandbox. Host lease owns termination. */
internal object LspIsolatedHealthLaunchPolicy {
    private val script = listOf(
        "set -eu;", "umask 077;",
        "runtime=\$(/usr/bin/mktemp -d /tmp/droide-lsp-health.XXXXXXXXXX);",
        "cleanup() { [ -d \"\$runtime\" ] && [ ! -L \"\$runtime\" ] || return 0; /bin/rmdir -- \"\$runtime\" 2>/dev/null || :; };",
        "trap cleanup EXIT; trap 'exit 143' TERM; trap 'exit 130' INT; trap 'exit 129' HUP;",
        "cd \"\$runtime\"; PATH=; export PATH;",
        // Original argv stays positional and foreground stdin is retained. Never sweep leftover
        // directories.
        "set +e; \"\$@\"; result=\$?; exit \"\$result\"",
    ).joinToString(" ")

    fun command(argv: List<String>): List<String> {
        require(argv.isNotEmpty() && argv.first().startsWith('/') && argv.all { '\u0000' !in it && '\n' !in it && '\r' !in it })
        return listOf("/bin/sh", "-c", script, "droide-lsp-health") + argv
    }
}
