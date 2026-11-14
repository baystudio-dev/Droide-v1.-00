package com.baystudio.droide.core

/** Stale/manual plans must not silently prepare a second workstation. */
internal object UbuntuExtensionPlanPolicy {
    // Exact Nim uses the existing LocalVendor plan with three explicit prerequisites, no separate server count.
    fun requireSupported(plan: ExtensionInstallPlan, localReviewedArtifact: Boolean) {
        if(plan.familyId==LocalUbuntuElpPolicy.FAMILY || plan is ExtensionInstallPlan.LocalVendor && plan.recipe.familyId==LocalUbuntuElpPolicy.FAMILY) {
            require(plan is ExtensionInstallPlan.LocalVendor&&plan.familyId==LocalUbuntuElpPolicy.FAMILY&&plan.requestedVersion==LocalUbuntuElpPolicy.VERSION&&
                plan.recipe.familyId==plan.familyId&&plan.nodeDependency==null&&plan.powerShellDependency==null&&plan.nimDependencies.isEmpty()) { "ELP cannot bypass its original typed installer" }
            LocalUbuntuElpPolicy.requireReviewed(plan.recipe)
            requireNotNull(plan.elpHealthPermit) { "ELP inactive: explicit selected-runtime health approval is required" }.requireCurrent()
        } else if(plan is ExtensionInstallPlan.LocalVendor) require(plan.elpHealthPermit==null)

        require(!LocalUbuntuGraphqlPolicy.isFamily(plan.familyId) ||
            (plan.familyId==LocalUbuntuGraphqlPolicy.FAMILY && plan is ExtensionInstallPlan.Recipe) ||
            (plan.familyId==LocalUbuntuGraphqlNodePolicy.FAMILY && plan is ExtensionInstallPlan.LocalVendor)) { "GraphQL cannot bypass its exact two-family installation" }
        if (plan.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY ||
            (plan is ExtensionInstallPlan.LocalVendor && plan.recipe.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY)) {
            require(plan is ExtensionInstallPlan.LocalVendor &&
                plan.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY &&
                plan.requestedVersion == LocalUbuntuGraphqlNodePolicy.VERSION &&
                plan.recipe.familyId == plan.familyId &&
                runCatching { LocalUbuntuGraphqlNodePolicy.requireReviewed(plan.recipe) }.isSuccess &&
                plan.nodeDependency == null && plan.powerShellDependency == null && plan.nimDependencies.isEmpty()) {
                "Only the exact private GraphQL Node plan without unrelated prerequisites is supported"
            }
        }
        require(!LocalUbuntuTomlPolicy.isFamily(plan.familyId) ||
            (plan.familyId==LocalUbuntuTomlPolicy.FAMILY && plan is ExtensionInstallPlan.Recipe) ||
            (plan.familyId==LocalUbuntuTomlNodePolicy.FAMILY && plan is ExtensionInstallPlan.LocalVendor)) { "Taplo cannot bypass its exact two-family installation" }
        require(!LocalUbuntuCSharpPolicy.isFamily(plan.familyId) || plan is ExtensionInstallPlan.LocalCSharp) { "C# cannot bypass its exact three-artifact typed installer" }
        val supported = when (plan) {
            is ExtensionInstallPlan.LocalCSharp -> plan.familyId==plan.recipe.familyId && plan.requestedVersion==plan.recipe.version &&
                runCatching { plan.recipe.validate() }.isSuccess
            is ExtensionInstallPlan.AndroidLocalComponent, is ExtensionInstallPlan.AndroidLocalWorkstation,
            is ExtensionInstallPlan.UserManaged, is ExtensionInstallPlan.UbuntuApt, is ExtensionInstallPlan.LocalRuby,
            is ExtensionInstallPlan.LocalVendor, is ExtensionInstallPlan.LocalVendorAlias,
            is ExtensionInstallPlan.LocalBundleAlias -> true
            is ExtensionInstallPlan.LocalCMake -> plan.recipe == LocalUbuntuCMakeCatalog.recipe &&
                plan.familyId == LocalUbuntuCMakePolicy.FAMILY && plan.requestedVersion == LocalUbuntuCMakePolicy.VERSION
            is ExtensionInstallPlan.Recipe -> LocalUbuntuNpmPolicy.supports(plan.recipe) &&
                if(plan.recipe.familyId == LocalUbuntuGraphqlPolicy.FAMILY) plan.familyId==LocalUbuntuGraphqlPolicy.FAMILY && plan.requestedVersion==LocalUbuntuGraphqlPolicy.VERSION &&
                    plan.nodeDependency==null && plan.vueHelperDependency==null && plan.tomlNodeDependency==null && plan.graphqlNodeDependency?.let { runCatching{LocalUbuntuGraphqlNodePolicy.requireReviewed(it)}.isSuccess }==true
                else if(plan.recipe.familyId == LocalUbuntuTomlPolicy.FAMILY) plan.familyId==LocalUbuntuTomlPolicy.FAMILY && plan.requestedVersion==LocalUbuntuTomlPolicy.VERSION &&
                    plan.nodeDependency==null && plan.vueHelperDependency==null && plan.graphqlNodeDependency==null && plan.tomlNodeDependency?.let { runCatching{LocalUbuntuTomlNodePolicy.requireReviewed(it)}.isSuccess }==true
                else if(plan.recipe.familyId == LocalUbuntuVuePolicy.FAMILY) plan.requestedVersion == LocalUbuntuVuePolicy.VERSION &&
                    plan.familyId == LocalUbuntuVuePolicy.FAMILY && plan.nodeDependency == null && plan.vueHelperDependency?.let {
                        runCatching { LocalUbuntuVueHelperPolicy.requireReviewed(it) }.isSuccess
                    } == true && plan.tomlNodeDependency==null && plan.graphqlNodeDependency==null else plan.vueHelperDependency == null && plan.tomlNodeDependency==null && plan.graphqlNodeDependency==null
            is ExtensionInstallPlan.GitHubRelease, is ExtensionInstallPlan.VendorOfficial -> localReviewedArtifact
            else -> false
        }
        require(supported) { "${plan.familyId} has no supported Ubuntu ARM64 installer. No alternate workstation was prepared." }
    }
}
