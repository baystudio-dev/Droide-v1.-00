package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Regression for installers which passed --version but could not build a real project. */
class UbuntuNativeToolchainCatalogTest {
    @Test fun cAndCppRecipesIncludeHeadersLinkerAndMakeWithRecommendationsDisabled() {
        val gcc = requireNotNull(UbuntuAptCatalog.find("toolchain.gcc"))
        val clang = requireNotNull(UbuntuAptCatalog.find("toolchain.llvm"))
        for (recipe in listOf(gcc, clang)) {
            assertTrue(recipe.familyId, "build-essential" in recipe.packages)
            assertTrue(recipe.familyId, "libc6-dev" in recipe.packages)
            assertTrue(recipe.familyId, "pkg-config" in recipe.packages)
            recipe.validate()
        }
        assertTrue(setOf("gcc", "g++", "cc", "c++").all { it in gcc.commands })
        assertTrue(setOf("clang", "clang++", "ld.lld").all { it in clang.commands })
        for ((language, compiler) in mapOf("c" to "gcc", "cpp" to "g++")) {
            val family = requireNotNull(UbuntuAptCatalog.languageRuntime[language])
            val recipe = requireNotNull(UbuntuAptCatalog.find(family))
            assertTrue("$language runner compiler must be projected", compiler in recipe.commands)
        }
    }

    @Test fun everyCargoEntryPointCarriesTheSameNativeLinkerBuildClosure() {
        for (family in listOf("toolchain.rust", "package.cargo", "test.cargo")) {
            val recipe = requireNotNull(UbuntuAptCatalog.find(family))
            assertTrue("$family cannot rely on APT recommendations", "build-essential" in recipe.packages)
            assertTrue("$family must support crate build scripts", "pkg-config" in recipe.packages)
            assertTrue("$family must permit verified HTTPS registries", "ca-certificates" in recipe.packages)
            assertEquals("/usr/bin/cargo", recipe.commands["cargo"])
        }
        val nimble = requireNotNull(UbuntuAptCatalog.find("package.nimble"))
        assertTrue(setOf("gcc", "libc6-dev").all { it in nimble.packages })
    }

    @Test fun promisedPipAndClippyCommandsHaveActualManagedAliases() {
        val pip = requireNotNull(UbuntuAptCatalog.find("package.pip"))
        assertEquals("/usr/bin/pip3", pip.commands["pip"])
        assertEquals("/usr/bin/pip3", pip.commands["pip3"])
        val clippy = requireNotNull(UbuntuAptCatalog.find("quality.clippy"))
        assertEquals("/usr/bin/clippy-driver", clippy.commands["clippy-driver"])
        for (recipe in listOf(pip, clippy)) {
            val family = requireNotNull(DevelopmentExtensionCatalog.byId(recipe.familyId))
            val choice = DevelopmentExtensionProjection.localVersions(family).single()
            assertEquals(recipe.commands.keys, choice.provides)
            assertEquals(ExecutionScope.LOCAL_LINUX_ARM64, choice.scope)
        }
    }

    @Test fun nativeDapUsesTheReviewedNobleArm64FileInsteadOfAnX86Payload() {
        val lldb = requireNotNull(UbuntuAptCatalog.find("debug.lldb"))
        assertEquals(listOf("lldb-18"), lldb.packages)
        assertEquals("/usr/bin/lldb-dap-18", lldb.commands["lldb-dap"])
        assertEquals(listOf("--help"), lldb.probeArgs("lldb-dap"))
        val family = requireNotNull(DevelopmentExtensionCatalog.byId(lldb.familyId))
        assertTrue(family.description.contains("ptrace"))
        assertEquals(setOf("lldb", "lldb-dap"), DevelopmentExtensionProjection.localVersions(family).single().provides)
        // An installed adapter must not turn the PRoot host capability into a certification.
        assertNotEquals(GuestCapabilitySupport.CERTIFIED,
            PackageRuntimeCompatibilityPolicy.capabilitySupport(
                DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64, DeclarativeGuestCapability.PTRACE))
    }
}
