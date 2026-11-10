package com.baystudio.droide.core

/** Source choices select an installer, not a fixed set of allowed version numbers. */
internal object ToolVersionRequests {
    fun select(items: List<ExtensionVersionState>, familyId: String, requested: String): ExtensionVersionState? {
        val version = ToolUpdatePlanner.request(requested)
        val family = items.firstOrNull()?.family ?: return null
        if (family.versions.any { it.installKind == ExtensionInstallKind.BUILT_IN }) return items.firstOrNull()
        val update = ExtensionUpdatePolicy.forFamily(family)
        if (!update.hasVersionPicker) {
            items.firstOrNull { it.version.version == version }?.let { return it }
            return items.first().let { template ->
                template.copy(version = template.version.copy(version = version), state = ExtensionState.UNAVAILABLE,
                    detail = update.description + "; no version was substituted.")
            }
        }
        if (familyId == LocalUbuntuZlsPolicy.FAMILY && version != "latest" && !LocalUbuntuZlsPolicy.supportsVersion(version)) {
            return items.first().let { template ->
                template.copy(version = template.version.copy(version = version), state = ExtensionState.UNAVAILABLE,
                    detail = update.description + "; no version was substituted.")
            }
        }
        val localOffers = items.filter { it.version.scope == ExecutionScope.LOCAL_LINUX_ARM64 &&
            it.version.installKind in setOf(ExtensionInstallKind.REVIEWED_RECIPE, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT) }
        if (localOffers.isNotEmpty() && localOffers.none { it.state in setOf(ExtensionState.AVAILABLE, ExtensionState.INSTALLED) }) {
            localOffers.firstOrNull { it.state == ExtensionState.UNAVAILABLE }?.let {
                return it.copy(version = it.version.copy(version = version))
            }
        }
        val native = LocalUbuntuVendorCatalog.find(familyId)
        val npm = UbuntuNpmCatalog.find(familyId)

        // New integrations are exact reviewed contracts, not an implicit request for a different
        // version. Existing npm families retain publisher selection.
        if (npm?.reviewedVersion != null && !npm.allowsPublisherUpdates) {
            val offered = items.firstOrNull { it.version.version == npm.reviewedVersion }
            if (version == npm.reviewedVersion) return offered
            val template = offered ?: items.firstOrNull() ?: return null
            return template.copy(version = template.version.copy(version = version), state = ExtensionState.UNAVAILABLE,
                detail = "Version $version is not a reviewed ${npm.packageName} integration. Choose the explicit ${npm.reviewedVersion} recipe; no version was substituted.")
        }
        val node = familyId == "runtime.node"
        val liveNative = native?.supportsPublisherVersionSelection == true
        val base = when {
            familyId == "plugin.opencode" && version == "latest" -> items.firstOrNull { it.version.version == LocalUbuntuVendorPolicy.VERSION_CHOICE }
            node -> items.firstOrNull { it.version.version == LocalUbuntuNodeDistribution.VERSION }
            npm != null -> items.firstOrNull { it.version.version == UbuntuNpmCatalog.VERSION }
            liveNative -> items.firstOrNull { it.version.version == LocalUbuntuVendorPolicy.VERSION_CHOICE }
                ?: localOffers.firstOrNull { it.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE }
            else -> null
        }
        if (base != null) {
            val selector = if (version != "latest") version else when {
                familyId == "plugin.opencode" -> LocalUbuntuVendorPolicy.VERSION_CHOICE
                node -> LocalUbuntuNodeDistribution.VERSION
                npm != null -> UbuntuNpmCatalog.VERSION
                else -> LocalUbuntuVendorPolicy.VERSION_CHOICE
            }
            return base.copy(version = base.version.copy(version = selector), state = if (base.state == ExtensionState.UNAVAILABLE) base.state else ExtensionState.AVAILABLE,
                detail = if (base.state == ExtensionState.UNAVAILABLE) base.detail else "Requested upstream version")
        }
        val existing = items.firstOrNull { it.version.version == version && it.state == ExtensionState.AVAILABLE }
        if (existing != null) return existing
        return ExtensionVersionState(family, ExtensionVersion(version, channel = "user-managed",
            installKind = ExtensionInstallKind.USER_MANAGED, scope = ExecutionScope.LOCAL_LINUX_ARM64,
            provides = family.versions.flatMap { it.provides }.toSet()), ExtensionState.AVAILABLE, "Update using the upstream package manager")
    }
}
