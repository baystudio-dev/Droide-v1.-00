package com.baystudio.droide.core
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Test
class GraphqlInstallTransactionTest {
    private fun denied(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    @Test fun unknownHealthBlocksRecoveryAndRetainsOwner() {
        val root=Files.createTempDirectory("authored-graphql-hold-").toFile()
        val paths=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuGraphqlPolicy.FAMILY,"5.7.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        check(paths.stage.mkdirs());val activation=LocalPackageInstallJournal.prepare(paths.stage);val hold=LocalPackageInstallJournal.beginGraphqlHealth(paths,activation)
        val token="a".repeat(32);val owner=java.io.File(root,"runtime-tmp/droide-graphql-health-$token");check(owner.mkdirs());Files.setAttribute(owner.toPath(),"unix:mode",448,NOFOLLOW_LINKS)
        val attrs=Files.readAttributes(owner.toPath(),"unix:dev,ino");val identity="${attrs["dev"]}:${attrs["ino"]}"
        LocalPackageInstallJournal.bindGraphqlHealthOwner(hold,owner.absolutePath,token,identity)
        LocalPackageInstallJournal.finishGraphqlHealth(hold,GraphqlTeardownReceipt.unknown("authored unresolved factory"))
        assertTrue(hold.file.isFile);assertTrue(hold.ownerFile.readText().contains(activation));assertTrue(owner.isDirectory)
        denied{LocalPackageInstallJournal.recover(paths,null)};assertTrue(paths.stage.exists())
    }
    @Test fun existingFamilyJournalNamesAndSidecarsRemainDistinct() {
        val old=LocalPackageInstallJournal.paths("/tmp/authored-graphql",LocalUbuntuTomlPolicy.FAMILY,"0.8.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        assertNotNull(old.tomlHealthHold);assertNull(old.graphqlHealthHold)
        val generic=LocalPackageInstallJournal.paths("/tmp/authored-graphql","runtime.node","22.23.3",LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE)
        assertNull(generic.graphqlHealthHold);assertNull(generic.tomlHealthHold)
    }
    private fun layout():LocalPackageInstallJournal.Paths {
        val root=Files.createTempDirectory("authored-graphql-transaction-").toFile()
        return LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuGraphqlPolicy.FAMILY,"5.7.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
    }
    private fun tree(root:java.io.File,id:String) {check(root.mkdirs());java.io.File(root,"DROIDE_ACTIVATION_ID").writeText(id)}
    private fun journal(paths:LocalPackageInstallJournal.Paths,candidate:String,previous:String?,stage:String=paths.stage.absolutePath):java.io.File {
        val file=java.io.File(paths.stage.parentFile,".${paths.stage.name}.graphql-activation-pending");check(file.parentFile!!.mkdirs()||file.parentFile!!.isDirectory)
        file.writeText(listOf("graphql-activation-v1",candidate,previous?:"-",stage,paths.final.absolutePath,paths.previous.absolutePath,"PREPARED").joinToString("\n",postfix="\n"));return file
    }
    private val candidate="11111111-1111-4111-8111-111111111111"
    private val previous="22222222-2222-4222-8222-222222222222"
    private val replacement="33333333-3333-4333-8333-333333333333"
    @Test fun missingActivationJournalPreservesUnknownStage() {
        val p=layout();tree(p.stage,candidate);denied{LocalPackageInstallJournal.recover(p,null)};assertTrue(p.stage.exists())
    }
    @Test fun malformedActivationJournalPreservesAllTrees() {
        val p=layout();tree(p.stage,candidate);val j=journal(p,candidate,null);j.writeText("malformed\n")
        denied{LocalPackageInstallJournal.recover(p,null)};assertTrue(p.stage.exists());assertTrue(j.exists())
    }
    @Test fun replacedCandidateFinalCannotBeRolledBackByPath() {
        val p=layout();tree(p.final,replacement);tree(p.previous,previous);val j=journal(p,candidate,previous)
        denied{LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(previous))}
        assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,replacement));assertTrue(p.previous.exists());assertTrue(j.exists())
    }
    @Test fun replacedPreviousTreeCannotBeAdoptedOrDeleted() {
        val p=layout();tree(p.final,candidate);tree(p.previous,replacement);val j=journal(p,candidate,previous)
        denied{LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(previous))};assertTrue(p.final.exists());assertTrue(p.previous.exists());assertTrue(j.exists())
    }
    @Test fun relocatedStageReceiptRejectsBeforeAnyCleanup() {
        val p=layout();tree(p.stage,candidate);val j=journal(p,candidate,null,p.stage.absolutePath+"-other")
        denied{LocalPackageInstallJournal.recover(p,null)};assertTrue(p.stage.exists());assertTrue(j.exists())
    }
    @Test fun interruptedCommittedActivationRetiresOnlyItsPreviousIdentity() {
        val p=layout();tree(p.final,candidate);tree(p.previous,previous);val j=journal(p,candidate,previous)
        LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(candidate))
        assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,candidate));assertFalse(p.previous.exists());assertFalse(j.exists())
    }
    @Test fun interruptedUncommittedActivationRestoresExactPrevious() {
        val p=layout();tree(p.final,candidate);tree(p.previous,previous);val j=journal(p,candidate,previous)
        LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(previous))
        assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,previous));assertFalse(p.previous.exists());assertFalse(j.exists())
    }
    @Test fun prepromotionFailureDiscardsOnlyExactOwnedStage() {
        val p=layout();tree(p.final,previous);tree(p.stage,candidate);val j=journal(p,candidate,previous)
        LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(previous))
        assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,previous));assertFalse(p.stage.exists());assertFalse(j.exists())
    }
    @Test fun registryMismatchPreservesCandidateAndBackup() {
        val p=layout();tree(p.final,candidate);tree(p.previous,previous);val j=journal(p,candidate,previous)
        denied{LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(replacement))};assertTrue(p.final.exists());assertTrue(p.previous.exists());assertTrue(j.exists())
    }
}
