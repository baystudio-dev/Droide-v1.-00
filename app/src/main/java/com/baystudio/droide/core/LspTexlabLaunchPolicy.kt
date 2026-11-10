package com.baystudio.droide.core

/** Private IPC namespace, not a filesystem/process sandbox. The host lease owns termination. */
internal object LspTexlabLaunchPolicy {
    private fun script(health: Boolean): String = listOf(
        "set -eu;",
        "umask 077;",
        "runtime=\$(/usr/bin/mktemp -d /tmp/droide-texlab.XXXXXXXXXX);",
        "cleanup() { [ -d \"\$runtime\" ] && [ ! -L \"\$runtime\" ] || return 0; /bin/rm -f -- \"\$runtime/texlab.sock\"; /bin/rmdir -- \"\$runtime\" 2>/dev/null || :; };",
        "trap cleanup EXIT; trap 'exit 143' TERM; trap 'exit 130' INT; trap 'exit 129' HUP;",
        "export XDG_RUNTIME_DIR=\"\$runtime\";",
        if (health) "cd \"\$runtime\";" else ":;",
        // Foreground execution preserves stdin; argv is never interpolated into code. Hard-kill cleanup
        // is best effort. Never recursively remove runtime contents.
        "set +e; \"\$@\"; result=\$?; exit \"\$result\"",
    ).joinToString(" ")

    val editorIdentity: String get() = script(false)

    fun command(argv: List<String>, health: Boolean = false): List<String> {
        require(argv.isNotEmpty() && argv.first().startsWith('/') && argv.all { '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Texlab launcher requires an absolute command and valid arguments"
        }
        return listOf("/bin/sh", "-c", script(health), "droide-texlab") + argv
    }
}
