package com.baystudio.droide.core

internal data class TerminalGuideSection(val title: String, val description: String, val examples: List<String>)
internal data class TerminalGuide(val label: String, val summary: String, val sections: List<TerminalGuideSection>)

/** Examples describe this backend, never certify installed tools or execute commands. */
internal object TerminalCommandGuide {
    fun forProfile(profile: TerminalManager.Profile): TerminalGuide {
        val basics = TerminalGuideSection("Shell basics", "Show the current folder, list files, read a file, or change directory. Replace paths with your own.",
            listOf("pwd", "ls -la", "cat README.md", "cd .."))
        val tools = TerminalGuideSection("Check installed tools", "Check this terminal's PATH before using a tool. A missing command does not mean Ubuntu needs to be downloaded again. Agent environment_probe checks the selected tab without installing anything.",
            listOf("for tool in git curl wget node npm python3; do command -v \"\$tool\" || printf '%s missing\\n' \"\$tool\"; done"))
        val legacy = TerminalGuide("Legacy terminal", "This project uses Ubuntu ARM64. Open the Ubuntu terminal for commands and dependency installation.", listOf(basics))
        return when (profile) {
            TerminalManager.Profile.LOCAL -> legacy
            TerminalManager.Profile.LOCAL_LINUX_ARM64 -> TerminalGuide("Linux · Ubuntu",
                "Use Extensions or the Droide CLI to install workspace tools. Check their versions before building.",
                listOf(basics, tools,
                    TerminalGuideSection("SSH connections", "Replace user and server with your own values. Enter the command without Markdown backticks. A password prompt deliberately shows no typed characters. ConnectTimeout limits connection setup, not the connected session. Use -vv to locate a stalled connection; for a private/VPN address, make sure the VPN includes Droide. Verify the server fingerprint before accepting it. If the log stalls after TCP connects, try -o IPQoS=none.",
                        listOf("droide pkg install cli.ssh", "ssh -o ConnectTimeout=15 user@server",
                            "ssh -vv -o ConnectTimeout=15 -o ConnectionAttempts=1 user@server")),
                    TerminalGuideSection("Managed tools", "These commands use the same package authority as Extensions. Install only missing tools; healthy packages and the shared Ubuntu guest are reused. Installing requires network access when the requested package is not cached.",
                        listOf("droide pkg list", "droide pkg search git", "droide pkg install cli.git", "droide pkg install cli.curl", "droide pkg install cli.wget", "git --version")),
                    TerminalGuideSection("Install and update tools", "Use the same package installer as Extensions. Latest or an exact version can be selected; user-installed tools in this Ubuntu guest remain usable. Existing healthy dependencies are reused.",
                        listOf("droide pkg install plugin.opencode", "droide pkg install plugin.codex@latest", "npm install -g opencode-ai@latest", "hash -r")),
                    TerminalGuideSection("Node.js and npm", "Install runtime.node once: it provides node, npm and npx together. User global npm packages stay outside the sealed runtime.",
                        listOf("droide pkg install runtime.node", "node --version", "npm --version", "npm install")),
                    TerminalGuideSection("Ubuntu packages", "The Ubuntu guest uses apt. The default guest user can install packages without sudo. Native programs must support this guest's architecture; desktop build instructions may need adaptation.",
                        listOf("apt --help", "apt search cmake", "uname -m", "apt-get update && apt-get --no-remove install cmake")),
                    TerminalGuideSection("Python project packages", "Install runtime.python once. Create a project venv only when it does not already exist; keep custom environments. Use that interpreter with -m pip for project libraries. Activation applies only to this shell. For IDE Python Run and detected Pytest, explicitly save .droide/python.json with {\"interpreter\":\".venv/bin/python\"}; this selects an existing environment without creating or activating it. Debug/LSP and custom tasks retain their own configuration. An externally-managed-environment error means this system interpreter is protected: use the selected project venv for libraries. Standalone CLI applications can use Extensions per-tool venvs or pipx; pipx is not a project library environment.",
                        listOf("droide pkg install runtime.python", "python3 -m venv .venv", ".venv/bin/python -m pip install requests", ".venv/bin/python -m pip --version", ".venv/bin/python main.py", ". .venv/bin/activate")),
                    TerminalGuideSection("Publisher downloads", "Use the publisher’s Linux ARM64 asset URL. Stop on HTTP errors and verify its published checksum before extracting or running it. Replace the URL and checksum below.",
                        listOf("curl --fail --location --show-error --output tool.tar.gz 'https://publisher.example/linux-arm64/tool.tar.gz'",
                            "printf '%s  %s\\n' 'PUBLISHER_SHA256' 'tool.tar.gz' | sha256sum --check -"))))
            TerminalManager.Profile.LOCAL_QEMU, TerminalManager.Profile.DEVICE_ADB -> legacy
        }
    }
}
