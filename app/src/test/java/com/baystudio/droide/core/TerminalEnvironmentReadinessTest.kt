package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalEnvironmentReadinessTest {
    @Test fun ubuntuSuggestionsPointToExistingAuthorityRecipesAndBundledNodeCommands() {
        for (command in listOf("git", "curl", "wget", "python", "python3", "pip", "pip3")) {
            val hint = requireNotNull(TerminalEnvironmentReadiness.missing(command, TerminalManager.Profile.LOCAL_LINUX_ARM64))
            val recipe = requireNotNull(UbuntuAptCatalog.find(requireNotNull(hint.managedFamily)))
            assertTrue("$command must be provided by ${recipe.familyId}", command in recipe.commands)
            assertEquals(listOf("droide pkg install ${recipe.familyId}"), hint.commands)
        }
        val node = LocalUbuntuNodeDistribution.recipe("22.23.3", "a".repeat(64))
        for (command in listOf("node", "npm", "npx")) {
            val hint = requireNotNull(TerminalEnvironmentReadiness.missing(command, TerminalManager.Profile.LOCAL_LINUX_ARM64))
            assertEquals(node.familyId, hint.managedFamily)
            assertTrue(command == node.command || command in node.treeAuxCommands)
        }
        for (command in listOf("pnpm", "yarn")) {
            val hint = requireNotNull(TerminalEnvironmentReadiness.missing(command, TerminalManager.Profile.LOCAL_LINUX_ARM64))
            assertEquals(command, UbuntuNpmCatalog.find(requireNotNull(hint.managedFamily))?.command)
        }
    }

    @Test fun alpineSuggestionsNeverInstallUbuntuOrOfferGlibcManagedNode() {
        for (command in listOf("git", "curl", "wget", "node", "npm", "npx", "python3", "pip", "pip3")) {
            val hint = requireNotNull(TerminalEnvironmentReadiness.missing(command, TerminalManager.Profile.LOCAL_QEMU))
            assertNull(hint.managedFamily)
            assertTrue(hint.commands.all { it.startsWith("apk add ") && !it.contains("droide") && !it.contains("apt") })
        }
        val pythonAlias = requireNotNull(TerminalEnvironmentReadiness.missing("python", TerminalManager.Profile.LOCAL_QEMU))
        assertEquals(listOf("apk add python3"), pythonAlias.commands)
        assertTrue(pythonAlias.detail.contains("does not guarantee a python alias"))
        assertNull(TerminalEnvironmentReadiness.missing("bun", TerminalManager.Profile.LOCAL_QEMU))
    }

    @Test fun localDeviceAndUnknownProfilesCannotSilentlyInstallIntoAnotherBackend() {
        for (profile in listOf(TerminalManager.Profile.LOCAL, TerminalManager.Profile.DEVICE_ADB)) {
            val hint = requireNotNull(TerminalEnvironmentReadiness.missing("npm", profile))
            assertTrue(hint.commands.isEmpty())
            assertNull(hint.managedFamily)
        }
        assertNull(TerminalEnvironmentReadiness.missing("npm", null))
        assertNull(TerminalEnvironmentReadiness.missing("unreviewed-tool", TerminalManager.Profile.LOCAL_LINUX_ARM64))
    }

    @Test fun guidesOfferOnlyUbuntuForProjectCommands() {
        val local = TerminalCommandGuide.forProfile(TerminalManager.Profile.LOCAL)
        assertTrue(local.summary.contains("uses Ubuntu ARM64"))
        assertFalse(local.sections.flatMap { it.examples }.any { it.startsWith("apk") })
        val alpine = TerminalCommandGuide.forProfile(TerminalManager.Profile.LOCAL_QEMU)
        assertTrue(alpine.summary.contains("uses Ubuntu ARM64"))
        assertFalse(alpine.sections.flatMap { it.examples }.any { it.startsWith("droide pkg install") || it.startsWith("apt") })
        val ubuntu = TerminalCommandGuide.forProfile(TerminalManager.Profile.LOCAL_LINUX_ARM64)
        assertTrue(ubuntu.sections.any { it.description.contains("provides node, npm and npx together") })
        val python = ubuntu.sections.single { it.title == "Python project packages" }
        assertTrue(python.description.contains("Activation applies only to this shell"))
        assertTrue(python.description.contains(".droide/python.json"))
        assertTrue(python.description.contains("Debug/LSP and custom tasks retain their own configuration"))
        assertTrue(python.examples.contains(".venv/bin/python main.py"))
    }
}
