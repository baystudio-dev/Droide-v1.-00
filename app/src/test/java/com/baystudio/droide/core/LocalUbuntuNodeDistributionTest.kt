package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNodeDistributionTest {
    @Test fun globalPackageStateDefaultsOutsideTheSdkAndExplicitEnvironmentPrefixesSurvive() {
        val root = Files.createTempDirectory("droide node '").toFile()
        try {
            val tree = File(root, "tree")
            val node = File(tree, "node-v22.23.3-linux-arm64/bin/node")
            node.parentFile?.mkdirs()
            node.writeText("#!/bin/sh\nprintf '%s\\n' \"${'$'}{npm_config_prefix-UNSET}\" \"${'$'}@\"\n")
            check(node.setExecutable(true))
            for (command in listOf("npm", "npx")) {
                val wrapper = File(root, command).apply { writeText(LocalUbuntuNodeDistribution.guestWrapper("22.23.3", command, tree.path)) }
                fun invoke(env: Map<String, String>): List<String> {
                    val process = ProcessBuilder("/bin/sh", wrapper.path, "argument with spaces", "$(literal)").apply {
                        environment().remove("npm_config_prefix"); environment().remove("NPM_CONFIG_PREFIX"); environment().putAll(env)
                    }.start()
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
                    return process.inputStream.bufferedReader().readLines()
                }
                val defaults = invoke(emptyMap())
                assertEquals("/root/.local", defaults[0])
                assertEquals(listOf("argument with spaces", "$(literal)"), defaults.takeLast(2))
                assertEquals("/user selected", invoke(mapOf("npm_config_prefix" to "/user selected"))[0])
                assertEquals("/upper selected", invoke(mapOf("NPM_CONFIG_PREFIX" to "/upper selected"))[0])
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun nodeDoesNotAcquireNpmConfiguration() {
        val wrapper = LocalUbuntuNodeDistribution.guestWrapper("22.23.3", "node", "/owned tree")
        assertFalse(wrapper.contains("npm_config_prefix"))
        assertTrue(wrapper.contains("\"\$@\""))
    }

    @Test fun malformedVersionOrAnArbitraryCommandCannotUseTheNodeAdapter() {
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuNodeDistribution.guestWrapper("24.0.0; shell", "npm", "/owned") }
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuNodeDistribution.guestWrapper("22.23.3", "shell", "/owned") }
    }
}
