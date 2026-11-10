package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Lifetime holds are tiny admission records, never locks held across hash/probe/user waits. */
internal class ElpPackageActivityHold internal constructor(internal val root:String,internal val token:String,val activationIdentity:String) {
    fun release(receipt:ElpTeardownReceipt) {
        if(!receipt.complete||!receipt.directChildReaped||!receipt.groupEmpty||!receipt.birthCustodyVerified||!receipt.factorySettled||
            !receipt.writersSettled||!receipt.readersSettled||!receipt.closeAttemptsSettled||!receipt.privateCleanupVerified)return
        LocalUbuntuElpLaunchAuthority.release(this)
    }
}

internal class ElpInstallerClaim internal constructor(internal val root:String,internal val token:String) {
    internal fun releaseNeverEntered() = LocalUbuntuElpLaunchAuthority.releaseInstaller(this)
    internal fun release(receipt:ElpTeardownReceipt) {
        if(!receipt.complete||!receipt.directChildReaped||!receipt.groupEmpty||!receipt.birthCustodyVerified||!receipt.factorySettled||
            !receipt.writersSettled||!receipt.readersSettled||!receipt.closeAttemptsSettled||!receipt.privateCleanupVerified)return
        LocalUbuntuElpLaunchAuthority.releaseInstaller(this)
    }
}
internal object LocalUbuntuElpLaunchAuthority {
    private val installers=HashMap<String,ElpInstallerClaim>()
    fun acquireInstaller(appRoot:String):ElpInstallerClaim = ElpGenerationAdmission.withAdmission {
        requireNoActivity(appRoot)
        ElpInstallerClaim(appRoot,UUID.randomUUID().toString()).also{installers[appRoot]=it}
    }
    internal fun releaseInstaller(claim:ElpInstallerClaim) = ElpGenerationAdmission.withAdmission {
        check(installers[claim.root]===claim) { "ELP installer claim changed; custody retained" }
        installers.remove(claim.root);Unit
    }

    private val activities=HashMap<String,MutableMap<String,String>>()
    fun acquireActivity(appRoot:String,activationIdentity:String):ElpPackageActivityHold = ElpGenerationAdmission.withAdmission {
        check(installers[appRoot]==null) { "ELP installation or health retains package custody" }
        require(activationIdentity.matches(Regex("[0-9a-f-]{36}")))
        val token=UUID.randomUUID().toString();activities.getOrPut(appRoot){HashMap()}[token]=activationIdentity
        ElpPackageActivityHold(appRoot,token,activationIdentity)
    }
    internal fun release(hold:ElpPackageActivityHold) = ElpGenerationAdmission.withAdmission {
        val map=requireNotNull(activities[hold.root]);require(map[hold.token]==hold.activationIdentity);map.remove(hold.token)
        if(map.isEmpty())activities.remove(hold.root)
    }
    fun requireNoActivity(appRoot:String,installer:ElpInstallerClaim?=null) = ElpGenerationAdmission.withAdmission {
        check(activities[appRoot].isNullOrEmpty()) { "ELP owned process cleanup is unresolved; package generation retained" }
        val current=installers[appRoot]
        check(if(installer==null)current==null else installer.root==appRoot&&current===installer) {
            "ELP installer ownership is unresolved or does not match this mutation"
        }
    }
    fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}) = LocalUbuntuElpInstaller.provePackage(appRoot,record,active)
    suspend fun inspect(appRoot:String,record:ManagedPackageRecord,records:List<ManagedPackageRecord>,selections:Map<String,String>,discovered:String,deadline:ElpDeadline):ElpManagedLaunchIdentity? {
        val context=currentCoroutineContext();val active={context.ensureActive();deadline.check()}
        return try {
            require(record.familyId==LocalUbuntuElpPolicy.FAMILY&&record.active&&records.count{it.familyId==record.familyId&&it.version==record.version}==1)
            require(selections[record.familyId] in setOf(null,record.version))
            require(LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot).singleOrNull{it.familyId==record.familyId}==record)
            val proof=provePackage(appRoot,record,active)
            val wrapper="${LocalUbuntuElpPolicy.ROOT}/payload/bin/elp"
            require(discovered in setOf("elp",wrapper,"/opt/droide/bin/elp"))
            if(discovered=="/opt/droide/bin/elp") require(NimRuntimeClosure.readText(File(appRoot,"managed/ubuntu-bin/elp"),65536,active)=="#!/bin/sh\nexec '$wrapper' \"\$@\"\n")
            identity(record,proof,discovered,LocalUbuntuElpPolicy.EXECUTABLE,
                LocalUbuntuElpPolicy.sha((selections.filterKeys{it==record.familyId}.toSortedMap().toString()+discovered).toByteArray()))
        } catch(c:kotlinx.coroutines.CancellationException){throw c}catch(_:Exception){null}
    }
    fun identity(record:ManagedPackageRecord,proof:ElpPackageProof,discovered:String,launch:String,selected:String):ElpManagedLaunchIdentity =
        ElpManagedLaunchIdentity(record.familyId,record.version,LocalUbuntuElpPolicy.POLICY,proof.receiptSha256,proof.sealSha256,selected,discovered,launch,
            proof.activationId,proof.memberSha256,proof.memberBytes,proof.helpersSha256)
    fun prove(appRoot:String,identity:ElpManagedLaunchIdentity,records:List<ManagedPackageRecord>,deadline:ElpDeadline,active:()->Unit={}) {
        require(identity.familyId==LocalUbuntuElpPolicy.FAMILY&&identity.version==LocalUbuntuElpPolicy.VERSION&&identity.sourcePolicyRevision==LocalUbuntuElpPolicy.POLICY)
        val record=records.single{it.familyId==identity.familyId&&it.version==identity.version}
        val proof=provePackage(appRoot,record){active();deadline.check()}
        require(record.active&&proof.receiptSha256==identity.receiptSha256&&proof.sealSha256==identity.sealSha256&&proof.activationId==identity.activationIdentity&&
            proof.memberSha256==identity.originalMemberSha256&&proof.memberBytes==identity.originalMemberBytes&&proof.helpersSha256==identity.helpersSha256)
    }
}
