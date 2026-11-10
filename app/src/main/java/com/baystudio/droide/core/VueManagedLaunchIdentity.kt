package com.baystudio.droide.core

/** Immutable, health-free identity. Full selected package proof occurs at protected creation. */
data class VueManagedLaunchIdentity(
    val familyId:String, val version:String, val helperFamilyId:String, val helperVersion:String,
    val sourcePolicyRevision:String, val receiptSha256:String, val helperReceiptSha256:String,
    val sealSha256:String, val helperSealSha256:String, val selectedPackageIdentity:String,
    val discoveredExecutable:String, val launchExecutable:String, val serverExecutable:String,
    val typescriptLspExecutable:String, val typescriptServerExecutable:String, val typescriptLibrary:String,
    val pluginExecutable:String, val helperExecutable:String, val npmRoot:String,
    val activationIdentity:String, val helperActivationIdentity:String, val helperConfigSha256:String,
    val helperSourceBindingSha256:String, val closureSha256:String,
) {
    val consentIdentity:String get()=LspVueLaunchPolicy.sha(toString().toByteArray(Charsets.UTF_8))
}
