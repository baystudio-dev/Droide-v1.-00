package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** The C# tuple owns all three original archives independently. No Vue/Nim receipt grants authority here. */
internal object LocalUbuntuCSharpLaunchAuthority {
    const val FILE_NAME = "CSHARP_AUTHORITY.json"
    const val CLOSURE_FILE = "CSHARP_CLOSURE.json"
    const val HEALTH_SEED = "csharp-health-identity.json"
    const val POLICY = LspCSharpLaunchPolicy.PROFILE
    val helperPaths = listOf(LspCSharpLaunchPolicy.SUPERVISOR, LspCSharpLaunchPolicy.HELD_LAUNCHER,
        LspCSharpLaunchPolicy.IDENTITY, LspCSharpLaunchPolicy.HEALTH)
    val expectedHelperHashes: Map<String, String> = mapOf(
        "payload/csharp-health.cjs" to "64780056283170b04ad25c4ba7ec553f9ca534b144419105e6691b670703d161",
        "payload/csharp-held-launcher.cjs" to "5e86580f403c78d07283f0e3504d9ef1dd157dffaf3f42e2e8e26b15c397e605",
        "payload/csharp-identity.cjs" to "10c7e873b83ba5ab15e4ac0a23ed5148c249689d3a3ac1b46059c15030f446ef",
        "payload/csharp-supervisor.cjs" to "59ef0c811e53053c2577f39db6b60fccf2a35b77d8d0c88feb066ac60fd067f7"
    )
    internal fun objectAt(file: File, limit: Int = 65_536, active: () -> Unit = {}): JsonObject {
        active(); return CSharpControlRecords.strict(CSharpControlRecords.readBytes(file, limit))
    }
    private fun selected(records: List<ManagedPackageRecord>, selections: Map<String, String>, family: String, version: String): ManagedPackageRecord {
        require(selections[family] == null || selections[family] == version) { "Unsupported explicitly selected C# artifact" }
        return records.single { it.familyId == family && it.version == version }
    }
    private fun requireTuple(server: CSharpPackageProof, sdk: CSharpPackageProof, helper: CSharpPackageProof) {
        require(server.record.familyId == "lsp.csharp-ls" && sdk.record.familyId == "runtime.csharp-sdk" && helper.record.familyId == "runtime.csharp-helper")
        require(server.dependencyReceipts == mapOf(sdk.record.familyId to sdk.receiptSha256, helper.record.familyId to helper.receiptSha256))
        require(sdk.dependencyReceipts.isEmpty() && helper.dependencyReceipts.isEmpty())
        require(server.helpersSha256 == expectedHelperHashes && expectedHelperHashes.size == 4)
        require(server.closureFiles.entries.containsAll(sdk.closureFiles.entries) && server.closureFiles.entries.containsAll(helper.closureFiles.entries))
    }
    suspend fun inspect(appRoot: String, server: ManagedPackageRecord, records: List<ManagedPackageRecord>,
        selections: Map<String, String>, discovered: String): CSharpManagedLaunchIdentity? {
        val context = currentCoroutineContext(); val active = { context.ensureActive() }
        return try {
            require(records.size <= 512 && records.map { it.familyId to it.version }.distinct().size == records.size)
            CSharpSdkSelection.requireExplicitSelections(selections)
            require(server == selected(records, selections, "lsp.csharp-ls", "0.28.0"))
            val sdk = selected(records, selections, "runtime.csharp-sdk", "10.0.402")
            val helper = selected(records, selections, "runtime.csharp-helper", "22.23.3")
            val effective = LocalLinuxPackageEnvironment.selectedRecords(records, selections, appRoot)
            for (record in listOf(server, sdk, helper)) require(effective.singleOrNull { it.familyId == record.familyId } == record)
            val a = LocalUbuntuCSharpInstaller.inspectPackage(appRoot, server, active)
            val d = LocalUbuntuCSharpInstaller.inspectPackage(appRoot, sdk, active)
            val h = LocalUbuntuCSharpInstaller.inspectPackage(appRoot, helper, active)
            requireTuple(a, d, h)
            val launch = "${LspCSharpLaunchPolicy.ROOT}/payload/bin/csharp-ls"
            require(discovered in setOf("csharp-ls", launch, "/opt/droide/bin/csharp-ls"))
            val projection = if (discovered == "/opt/droide/bin/csharp-ls")
                CSharpControlRecords.readBytes(File(appRoot,"managed/ubuntu-bin/csharp-ls"),65_536).toString(Charsets.UTF_8).also {
                    require(it == "#!/bin/sh\nexec '$launch' \"${'$'}@\"\n")
                } else "version-specific"
            val selectedHash = LspCSharpLaunchPolicy.sha((selections.filterKeys { it in setOf(server.familyId,sdk.familyId,helper.familyId) }
                .toSortedMap().toString() + ":" + projection).toByteArray())
            CSharpManagedLaunchIdentity(server.familyId,server.version,sdk.familyId,sdk.version,helper.familyId,helper.version,POLICY,
                a.receiptSha256,d.receiptSha256,h.receiptSha256,a.sealSha256,d.sealSha256,h.sealSha256,selectedHash,
                discovered,launch,"${LspCSharpLaunchPolicy.ROOT}/payload/tree/tools/net10.0/any/CSharpLanguageServer.dll",
                "${LspCSharpLaunchPolicy.SDK_ROOT}/dotnet",LspCSharpLaunchPolicy.SDK_ROOT,LspCSharpLaunchPolicy.HELPER,
                a.activationId,d.activationId,h.activationId,a.closureSha256).also { require(LspCSharpLaunchPolicy.matchesIdentity(it)) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    fun provePackage(appRoot: String, record: ManagedPackageRecord, active: () -> Unit = {}) =
        LocalUbuntuCSharpInstaller.provePackage(appRoot,record,active)
    fun proveOwnedPackages(appRoot: String, server: ManagedPackageRecord, owned: List<ManagedPackageRecord>, active: () -> Unit = {}) {
        require(owned.size == 2)
        requireTuple(provePackage(appRoot,server,active),
            provePackage(appRoot,owned.single { it.familyId == "runtime.csharp-sdk" },active),
            provePackage(appRoot,owned.single { it.familyId == "runtime.csharp-helper" },active))
    }
    fun prove(appRoot: String, identity: CSharpManagedLaunchIdentity, records: List<ManagedPackageRecord>, active: () -> Unit) {
        require(LspCSharpLaunchPolicy.matchesIdentity(identity))
        val deadline = System.nanoTime() + 60_000_000_000L
        val bounded = { active(); check(System.nanoTime() <= deadline) { "C# full package proof deadline" } }
        val a=provePackage(appRoot,records.single { it.familyId==identity.familyId&&it.version==identity.version },bounded)
        val d=provePackage(appRoot,records.single { it.familyId==identity.sdkFamilyId&&it.version==identity.sdkVersion },bounded)
        val h=provePackage(appRoot,records.single { it.familyId==identity.helperFamilyId&&it.version==identity.helperVersion },bounded)
        requireTuple(a,d,h)
        require(listOf(a.receiptSha256,d.receiptSha256,h.receiptSha256)==listOf(identity.receiptSha256,identity.sdkReceiptSha256,identity.helperReceiptSha256))
        require(listOf(a.sealSha256,d.sealSha256,h.sealSha256)==listOf(identity.sealSha256,identity.sdkSealSha256,identity.helperSealSha256))
        require(listOf(a.activationId,d.activationId,h.activationId)==listOf(identity.activationIdentity,identity.sdkActivationIdentity,identity.helperActivationIdentity))
        require(a.closureSha256==identity.closureSha256)
    }
    fun guestWrapper(family: String, guestRoot: String): String {
        val roots = mapOf("lsp.csharp-ls" to LspCSharpLaunchPolicy.ROOT,
            "runtime.csharp-sdk" to LspCSharpLaunchPolicy.SDK_ROOT.removeSuffix("/payload/tree"),
            "runtime.csharp-helper" to LspCSharpLaunchPolicy.HELPER.substringBefore("/payload/tree"))
        require(roots[family]==guestRoot)
        return "#!/bin/sh\nset -eu\nprintf '%s\\n' 'C# requires its reviewed private owner; direct launch and generic health are unavailable.' >&2\nexit 64\n"
    }
}
