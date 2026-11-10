package com.baystudio.droide.core

/** Metadata witness only. Protected process creation separately re-proves the full original/runtime trees. */
data class GraphqlManagedLaunchIdentity(
    val familyId:String,val version:String,val runtimeFamilyId:String,val runtimeVersion:String,
    val sourcePolicyRevision:String,val receiptSha256:String,val runtimeReceiptSha256:String,
    val sealSha256:String,val runtimeSealSha256:String,val selectedPackageIdentity:String,
    val discoveredExecutable:String,val launchExecutable:String,val nodeExecutable:String,
    val activationIdentity:String,val runtimeActivationIdentity:String,val closureSha256:String,
    val sourceBindingSha256:String,val importReviewSha256:String,val noticeAdmissionSha256:String,
    val dataAdmissionSha256:String,val helpersSha256:Map<String,String>,
) {
    val consentIdentity:String get()=LspGraphqlLaunchPolicy.sha(toString().toByteArray(Charsets.UTF_8))
}
