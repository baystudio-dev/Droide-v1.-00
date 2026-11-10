package com.baystudio.droide.core

/** Display the reviewed pin honestly without changing resolver/provenance authority. */
internal object PackageSourceChoicePresentation {
    fun version(source: PackageSourceDefinition): String =
        if (source.vendorVersionStrategy == DeclarativeVendorVersionStrategy.PINNED)
            requireNotNull(source.vendorPinnedVersion) else PackageSourceCatalog.LATEST_OFFICIAL_VERSION

    fun note(source: PackageSourceDefinition): String =
        if (source.vendorVersionStrategy == DeclarativeVendorVersionStrategy.PINNED)
            "Reviewed official ${version(source)} · catalog-pinned SHA-256. ${source.provenanceUrl}"
        else "Latest official ${source.source.label} release."
}
