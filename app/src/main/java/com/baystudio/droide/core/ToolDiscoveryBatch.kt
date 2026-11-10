package com.baystudio.droide.core

/** One shell, only builtins: discovery must not fork a subprocess for every tool. */
internal object ToolDiscoveryBatch {
    fun command(commands: List<String>): String {
        require(commands.isNotEmpty() && commands.size <= 1024)
        require(commands.all { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) })
        return "for c in " + commands.distinct().joinToString(" ", transform = LocalExecutionSubstrate::shellQuote) +
            "; do printf '%s\\t' \"\$c\"; command -v -- \"\$c\" 2>/dev/null || printf '\\n'; done"
    }
    fun parse(output: String, commands: List<String>): Map<String, String> = output.lineSequence().mapNotNull { line ->
        val split = line.split('\t', limit = 2)
        if (split.size != 2 || split[0] !in commands || !split[1].startsWith('/')) null
        else split[0] to split[1]
    }.toMap()
}
