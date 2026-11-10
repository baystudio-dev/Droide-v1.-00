package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLanguageServerPlanTest {
    @Test fun tomlLibrariesAndPrivateRuntimeCountAsOneConfiguration() {
        ManagedLanguageServerPlan.validate()
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer(LspTomlProfile.ID))
        assertEquals("lsp.taplo-wasm", plan.packageFamilyId)
        assertEquals("0.8.0", plan.pinnedVersion)
        assertEquals(LspTomlProfile.COMMAND, plan.command)
        assertEquals(LanguageServerRuntimeKind.LINUX_ARM64_PROOT, plan.runtime)
        assertEquals(1, ManagedLanguageServerPlan.priority.count { it.packageFamilyId == "lsp.taplo-wasm" })
        assertTrue(ManagedLanguageServerPlan.priority.none { it.packageFamilyId == "runtime.taplo-node" })
        assertTrue(plan.requiredCommands.isEmpty())
        assertTrue(plan.notes.contains("runtime.taplo-node 22.23.3"))
    }

    @Test fun priorityPlanCoversCoreAuditTargets() {
        ManagedLanguageServerPlan.validate()
        val ids = ManagedLanguageServerPlan.priority.map { it.languageServerId }.toSet()
        assertTrue(setOf("python-pyright", "python-pylsp", "typescript", "clangd", "gopls", "rust-analyzer", "kotlin", "jdtls").all { it in ids })
    }

    @Test fun everyPlanCommandIsLaunchableByItsRegisteredServerSpec() {
        ManagedLanguageServerPlan.priority.forEach { plan ->
            val spec = LanguageServerRegistry.builtIns.firstOrNull { it.id == plan.languageServerId }
            assertNotNull(plan.languageServerId, spec)
            assertTrue(spec!!.commands.any { it.firstOrNull() == plan.command })
        }
    }

    @Test fun svelteIsOneExactNodeServerWithExplicitManagedPrerequisites() {
        val plan = requireNotNull(ManagedLanguageServerPlan.forServer("svelte-language-server"))
        assertEquals("lsp.svelte-language-server", plan.packageFamilyId)
        assertEquals("0.18.5", plan.pinnedVersion)
        assertEquals("svelteserver", plan.command)
        assertEquals(LanguageServerRuntimeKind.NODE, plan.runtime)
        assertEquals(setOf("node", "npm"), plan.requiredCommands)
        assertEquals(mapOf("node" to 22, "npm" to 10), plan.runtimeRequirements.associate { it.command to it.minimumMajor })
        assertEquals(1, ManagedLanguageServerPlan.priority.count { it.packageFamilyId == plan.packageFamilyId })
        assertTrue(plan.notes.contains("command discovery is not typed"))
        assertTrue(plan.notes.contains("target-device acceptance remain unverified"))
    }

    @Test fun runtimeRequirementsRemainExplicit() {
        assertEquals(LanguageServerRuntimeKind.NODE, ManagedLanguageServerPlan.forServer("python-pyright")?.runtime)
        assertEquals(LanguageServerRuntimeKind.ANDROID_NATIVE, ManagedLanguageServerPlan.forServer("gopls")?.runtime)
        val rust = ManagedLanguageServerPlan.forServer("rust-analyzer")!!
        assertEquals(LanguageServerRuntimeKind.ANDROID_NATIVE, rust.runtime)
        assertEquals("20260914", rust.pinnedVersion)
        val typescript = ManagedLanguageServerPlan.forServer("typescript")!!
        assertEquals(RuntimeVersion(22, 22, 2), typescript.runtimeRequirements.single().let { RuntimeVersion(it.minimumMajor, it.minimumMinor, it.minimumPatch) })
        val jdtls = ManagedLanguageServerPlan.forServer("jdtls")!!
        assertEquals(LanguageServerRuntimeKind.JVM, jdtls.runtime)
        assertEquals("1.61.0", jdtls.pinnedVersion)
        assertEquals(21, jdtls.runtimeRequirements.single().minimumMajor)
    }
    @Test fun graphqlIsOneConfigurationWithSeparatePrivateRuntime() {
        val plan=requireNotNull(ManagedLanguageServerPlan.forServer("graphql-local-schema"))
        assertEquals("lsp.graphql-library",plan.packageFamilyId);assertEquals("5.7.0",plan.pinnedVersion)
        assertEquals("droide-graphql-local-lsp",plan.command);assertEquals(LanguageServerRuntimeKind.LINUX_ARM64_PROOT,plan.runtime)
        assertEquals(1,ManagedLanguageServerPlan.priority.count{it.packageFamilyId==plan.packageFamilyId})
        assertTrue(plan.requiredCommands.isEmpty());assertTrue(plan.notes.contains("runtime.graphql-node 22.23.3"))
    }
}
