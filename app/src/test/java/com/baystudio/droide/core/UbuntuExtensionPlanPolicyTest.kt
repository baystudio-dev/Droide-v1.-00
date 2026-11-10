package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class UbuntuExtensionPlanPolicyTest {
    @Test fun legacyDevicePlanIsRejectedEvenWithLocalArtifactFlag() {
        val plan = ExtensionInstallPlan.Package("old.package", "Legacy package", "1", emptyList(), emptyList())
        for (reviewed in listOf(false, true)) {
            val failure = runCatching { UbuntuExtensionPlanPolicy.requireSupported(plan, reviewed) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("Ubuntu ARM64 installer"))
        }
    }

    @Test fun reviewedUbuntuNpmPlanPassesButUnknownRecipeIsRejected() {
        val recipe = requireNotNull(WorkstationInstallRecipeCatalog.find("plugin.opencode", "1.18.34"))
        val plan = ExtensionInstallPlan.Recipe(recipe.familyId, "OpenCode", recipe.version, recipe, components = emptyList())
        UbuntuExtensionPlanPolicy.requireSupported(plan, false)
        val unsupported = plan.copy(recipe = recipe.copy(familyId = "unknown.package", packageName = "unknown-package"))
        assertTrue(runCatching { UbuntuExtensionPlanPolicy.requireSupported(unsupported, true) }.exceptionOrNull() is IllegalArgumentException)
    }
}
