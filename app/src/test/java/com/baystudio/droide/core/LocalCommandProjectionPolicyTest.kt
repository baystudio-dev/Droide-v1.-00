package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LocalCommandProjectionPolicyTest {
    private val root = "/tmp/droide-command-policy"
    @Test fun everyAptIntegrationCanCoexistAndStillProvideEachCommand() {
        val records = UbuntuAptCatalog.entries.map(::receipt)
        val projected = LocalCommandProjectionPolicy.project(records, root)
        val commands = projected.flatMap { it.commands.keys }
        assertEquals(commands.size, commands.toSet().size)
        assertEquals(records.flatMap { it.commands.keys }.toSet(), commands.toSet())
        assertEquals(commands.toSet(), LocalCommandProjectionPolicy.project(records.reversed(), root).flatMap { it.commands.keys }.toSet())
    }
    @Test fun removingOneIntegrationKeepsItsSharedCommandAvailable() {
        val cargo = receipt(UbuntuAptCatalog.find("package.cargo")!!)
        val rust = receipt(UbuntuAptCatalog.find("toolchain.rust")!!)
        assertEquals(1, LocalCommandProjectionPolicy.project(listOf(cargo, rust), root).count { "cargo" in it.commands })
        assertTrue("cargo" in LocalCommandProjectionPolicy.project(listOf(rust), root).single().commands)
    }
    @Test fun arbitraryAndForgedCommandOwnersCannotBorrowAptEquivalence() {
        val cargo = receipt(UbuntuAptCatalog.find("package.cargo")!!)
        val rust = receipt(UbuntuAptCatalog.find("toolchain.rust")!!)
        for (bad in listOf(rust.copy(metadata = emptyMap()), rust.copy(abi = "x86_64"),
            rust.copy(commands = rust.commands + ("cargo" to "/tmp/user-cargo")))) {
            try { LocalCommandProjectionPolicy.project(listOf(cargo, bad), root); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    private fun receipt(recipe: UbuntuAptRecipe): ManagedPackageRecord {
        val path = "$root/packages/${recipe.familyId}/${recipe.version}/arm64-v8a"
        return ManagedPackageRecord(recipe.familyId, recipe.version, ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            commands = recipe.commands.keys.associateWith { "$path/payload/host-bin/$it" }, abi = "arm64-v8a",
            metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_APT))
    }
}
