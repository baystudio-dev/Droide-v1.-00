package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSecurityPolicyTest {
    @Test fun remoteExecutableMustStayInSystemApexOrDroideRoot() {
        val root = "/data/local/tmp/droide"
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("/system/bin/sh", root))
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("/apex/com.android.runtime/bin/linker64", root))
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root/managed/bin/gopls", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("/data/local/tmp/other/tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root/../other/tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root//tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("relative/tool", root))
    }

    @Test fun processArgumentsAndEnvironmentRejectControlCharacters() {
        ProcessSecurityPolicy.validateArgv(listOf("tool", "--stdio"))
        ProcessSecurityPolicy.validateEnvironment(mapOf("HOME" to "/data/local/tmp/droide/home"))
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSecurityPolicy.validateArgv(listOf("tool", "bad\narg"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSecurityPolicy.validateEnvironment(mapOf("BAD-KEY" to "value"))
        }
    }

    @Test fun guestCapabilityAdmissionAcceptsKnownPublisherAndNpmUserBins() {
        val commands = mapOf(
            "/root/.opencode/bin" to "opencode", "/root/.npm-global/bin" to "codex",
            "/root/.local/bin" to "claude", "/root/.local/share/pnpm" to "claude-agent-acp",
            "/root/.bun/bin" to "copilot", "/root/.cargo/bin" to "cargo", "/root/go/bin" to "gopls",
        )
        for ((directory, command) in commands) {
            val path = "$directory/$command"
            assertTrue(path, ProcessSecurityPolicy.isAllowedGuestExecutable(path))
            assertTrue(path, ProcessSecurityPolicy.isAllowedCapabilityExecutable(path, "/data/local/tmp/droide"))
            assertFalse("Guest admission must not change device launch authority", ProcessSecurityPolicy.isAllowedRemoteExecutable(path, "/data/local/tmp/droide"))
        }
        assertTrue(ProcessSecurityPolicy.isAllowedGuestExecutable("/usr/bin/node"))
        assertTrue(ProcessSecurityPolicy.isAllowedGuestExecutable("/bin/sh"))
    }

    @Test fun userBinAdmissionRejectsSiblingTraversalAndArbitraryRootPaths() {
        for (directory in listOf("/root/.opencode/bin", "/root/.npm-global/bin", "/root/.local/share/pnpm", "/root/.bun/bin")) {
            for (path in listOf("$directory-evil/tool", "$directory/../tool", "$directory/./tool", "$directory//tool", "$directory/tool\n", "$directory/tool\u0000")) {
                assertFalse(path, ProcessSecurityPolicy.isAllowedGuestExecutable(path))
                assertFalse(path, ProcessSecurityPolicy.isAllowedCapabilityExecutable(path, "/data/local/tmp/droide"))
            }
        }
        for (path in listOf("/root/.ssh/tool", "/root/tool", "/root/bin/tool", "/tmp/tool", "/opt/droide/unchecked/tool")) {
            assertFalse(path, ProcessSecurityPolicy.isAllowedGuestExecutable(path))
        }
    }
}
