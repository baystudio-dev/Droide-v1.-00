package com.baystudio.droide.core

data class TerminalLaunchSpec(
    val prefix: List<String> = emptyList(),
    val shell: String = "/system/bin/sh",
    val interactiveArgs: List<String> = listOf("-l", "-i"),
    val environment: Map<String, String> = emptyMap(),
    val interactiveEnvironment: Map<String, String> = emptyMap(),
) {
    init {
        ProcessSecurityPolicy.validateArgv(prefix + listOf(shell) + interactiveArgs)
        require(shell.isNotBlank()) { "Terminal shell is missing" }
        ProcessSecurityPolicy.validateEnvironment(environment)
        ProcessSecurityPolicy.validateEnvironment(interactiveEnvironment)
    }

    val interactiveArgv: List<String> get() = interactiveCommand()

    // An env -i guest launcher must receive the lease token after its environment reset.
    fun interactiveCommand(injectedEnvironment: Map<String, String> = emptyMap()): List<String> =
        command(listOf(shell) + interactiveArgs, interactiveEnvironment + injectedEnvironment)

    fun pipeCommand(injectedEnvironment: Map<String, String> = emptyMap()): List<String> =
        command(listOf(shell) + TerminalPipeShell.args(interactiveArgs),
            interactiveEnvironment + injectedEnvironment + TerminalPipeShell.environment)

    fun shellCommand(shellText: String, injectedEnvironment: Map<String, String> = emptyMap()): List<String> {
        require(shellText.length <= 32_000 && '\u0000' !in shellText) { "Shell command is too large or contains NUL" }
        ProcessSecurityPolicy.validateEnvironment(injectedEnvironment)
        val guestEnvironment = if (prefix.isEmpty()) emptyList() else injectedEnvironment.map { (key, value) -> "$key=$value" }
        return prefix + guestEnvironment + listOf(shell, "-c", shellText)
    }

    fun command(argv: List<String>, injectedEnvironment: Map<String, String> = emptyMap()): List<String> {
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(injectedEnvironment)
        require(argv.first().isNotBlank()) { "Executable is missing" }

        return if (prefix.isEmpty()) argv else prefix + injectedEnvironment.map { (key, value) -> "$key=$value" } + argv
    }

    val canReviveAsAndroidShell: Boolean
        get() = prefix.isEmpty() && shell == "/system/bin/sh" &&
            interactiveArgs in listOf(listOf("-l"), listOf("-l", "-i")) && environment.isEmpty() && interactiveEnvironment.isEmpty()
}
