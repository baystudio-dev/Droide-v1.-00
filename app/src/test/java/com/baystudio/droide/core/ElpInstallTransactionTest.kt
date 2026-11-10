package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

class ElpInstallTransactionTest {
    private fun denied(action:()->Unit){try{action();fail("Expected exact-custody rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun root()=System.getProperty("elp.test.root")?.let{Files.createTempDirectory(File(it).toPath(),"journal-")}?.toFile()?:Files.createTempDirectory("authored-elp-journal-").toFile()
    private fun paths(root:File)=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION,LocalUbuntuElpPolicy.KIND)
    private fun tree(root:File,id:String){check(root.mkdirs());File(root,"DROIDE_ACTIVATION_ID").writeText(id)}
    private val candidate="11111111-1111-4111-8111-111111111111"
    private val previous="22222222-2222-4222-8222-222222222222"
    private val other="33333333-3333-4333-8333-333333333333"
    private fun journal(p:LocalPackageInstallJournal.Paths,c:String=candidate,prev:String?=previous){check(p.elpActivationJournal!!.parentFile!!.mkdirs()||p.elpActivationJournal!!.parentFile!!.isDirectory);p.elpActivationJournal!!.writeText(listOf("elp-activation-v1",c,prev?:"-",p.stage.absolutePath,p.final.absolutePath,p.previous.absolutePath,"PREPARED").joinToString("\n",postfix="\n"))}
    @Test fun unknownHealthPreservesEveryTreeAndBlocksRecovery(){
        val r=root();val p=paths(r);tree(p.final,previous);tree(p.stage,candidate);LocalPackageInstallJournal.beginElpActivation(p,candidate,previous)
        val h=LocalPackageInstallJournal.beginElpHealth(p,candidate);val token="a".repeat(32);val owner=File(r,"runtime-tmp/droide-elp-health-$token");check(owner.mkdirs());Files.setPosixFilePermissions(owner.toPath(),PosixFilePermissions.fromString("rwx------"))
        val attrs=Files.readAttributes(owner.toPath(),"unix:dev,ino");LocalPackageInstallJournal.bindElpHealthOwner(h,owner.absolutePath,token,"${attrs["dev"]}:${attrs["ino"]}")
        LocalPackageInstallJournal.finishElpHealth(h,ElpTeardownReceipt(false,true,true,true,false,true,true,true,true,"factory unresolved"))
        denied{LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(previous))}
        assertTrue(h.file.isFile);assertTrue(h.ownerFile.isFile);assertTrue(owner.isDirectory);assertTrue(p.stage.isDirectory);assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,previous))
    }
    @Test fun cleanupCannotBeInferredFromParentExit(){
        val r=root();val p=paths(r);tree(p.stage,candidate);val h=LocalPackageInstallJournal.beginElpHealth(p,candidate)
        for(i in 0..8){val b=MutableList(9){true};b[i]=false;LocalPackageInstallJournal.finishElpHealth(h,ElpTeardownReceipt(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8]));assertTrue(h.file.exists())}
        denied{LocalPackageInstallJournal.finishElpHealth(h,ElpTeardownReceipt(true,true,true,true,true,true,true,true,true))};assertTrue(h.file.exists())
    }
    @Test fun committedAndUncommittedRecoveryUseExactIds(){
        val p=paths(root());tree(p.final,candidate);tree(p.previous,previous);journal(p);LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(candidate));assertTrue(LocalPackageInstallJournal.matchesActivation(p.final,candidate));assertFalse(p.previous.exists());assertFalse(p.elpActivationJournal!!.exists())
        val q=paths(root());tree(q.final,candidate);tree(q.previous,previous);journal(q);LocalPackageInstallJournal.recover(q,LocalPackageInstallJournal.Receipt(previous));assertTrue(LocalPackageInstallJournal.matchesActivation(q.final,previous));assertFalse(q.previous.exists());assertFalse(q.elpActivationJournal!!.exists())
    }
    @Test fun replacedStageAndPreviousAndRegistryRemainUntouched(){
        for(which in listOf("stage","previous","registry","journal")){
            val p=paths(root());tree(p.final,previous);tree(p.stage,if(which=="stage")other else candidate);journal(p)
            if(which=="previous")tree(p.previous,other)
            if(which=="journal")p.elpActivationJournal!!.writeText("malformed")
            denied{LocalPackageInstallJournal.recover(p,LocalPackageInstallJournal.Receipt(if(which=="registry")other else previous))}
            assertTrue(p.stage.exists());assertTrue(p.final.exists());assertTrue(p.elpActivationJournal!!.exists())
        }
    }
    @Test fun neverEnteredRetirementRequiresExactLocalCustody(){
        val p=paths(root());tree(p.stage,candidate);LocalPackageInstallJournal.beginElpActivation(p,candidate,null);val h=LocalPackageInstallJournal.beginElpHealth(p,candidate)
        LocalPackageInstallJournal.finishElpHealthNeverEntered(h);assertFalse(h.file.exists());assertTrue(p.stage.exists());assertTrue(p.elpActivationJournal!!.exists())
        LocalPackageInstallJournal.recover(p,null);assertFalse(p.stage.exists());assertFalse(p.elpActivationJournal!!.exists())
        for(change in listOf("entered","nonce","owner","activation")) {
            val q=paths(root());tree(q.stage,candidate);val hold=LocalPackageInstallJournal.beginElpHealth(q,candidate)
            when(change){"entered"->hold.enterRuntime();"nonce"->hold.file.writeText(other);"owner"->hold.ownerFile.writeText("unresolved");"activation"->File(q.stage,"DROIDE_ACTIVATION_ID").writeText(other)}
            denied{LocalPackageInstallJournal.finishElpHealthNeverEntered(hold)};assertTrue(hold.file.exists());assertTrue(q.stage.exists())
        }
    }
    @Test fun oldFamiliesKeepTheirOwnHolds(){
        val r=root().absolutePath;val generic=LocalPackageInstallJournal.paths(r,"runtime.node","22.23.3",LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE);assertNull(generic.elpHealthHold);assertNull(generic.elpActivationJournal)
        val graphql=LocalPackageInstallJournal.paths(r,"lsp.graphql-library","5.7.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM);assertNull(graphql.elpHealthHold);assertNotNull(graphql.graphqlHealthHold)
    }
}
