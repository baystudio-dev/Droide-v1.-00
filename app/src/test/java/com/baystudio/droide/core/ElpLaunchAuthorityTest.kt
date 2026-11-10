package com.baystudio.droide.core
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class ElpLaunchAuthorityTest {
    @Test fun generationHeldUntilEveryCleanupObligationSettles(){
        val root="/tmp/elp-authored-activity-${UUID.randomUUID()}";val hold=LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString())
        for(i in 0..8){val b=MutableList(9){true};b[i]=false;hold.release(ElpTeardownReceipt(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8]));var denied=false;try{LocalUbuntuElpLaunchAuthority.requireNoActivity(root)}catch(_:IllegalStateException){denied=true};assertTrue(denied)}
        hold.release(ElpTeardownReceipt(true,true,true,true,true,true,true,true,true));LocalUbuntuElpLaunchAuthority.requireNoActivity(root)
    }
    @Test fun distinctOwnersDoNotReleaseEachOther(){
        val root="/tmp/elp-authored-activity-${UUID.randomUUID()}";val a=LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString());val b=LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString());val done=ElpTeardownReceipt(true,true,true,true,true,true,true,true,true)
        a.release(done);var denied=false;try{LocalUbuntuElpLaunchAuthority.requireNoActivity(root)}catch(_:IllegalStateException){denied=true};assertTrue(denied);b.release(done);LocalUbuntuElpLaunchAuthority.requireNoActivity(root)
    }
    @Test fun installerClaimExcludesActivityAndUnrelatedMutationUntilSettlement(){
        val root="/tmp/elp-authored-install-${UUID.randomUUID()}";val claim=LocalUbuntuElpLaunchAuthority.acquireInstaller(root)
        ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString())}
        ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.acquireInstaller(root)}
        ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.requireNoActivity(root)}
        LocalUbuntuElpLaunchAuthority.requireNoActivity(root,claim)
        claim.releaseNeverEntered();LocalUbuntuElpLaunchAuthority.requireNoActivity(root)
    }
    @Test fun unknownInstallerCleanupAndForgedClaimCannotReleaseOwner(){
        val root="/tmp/elp-authored-install-${UUID.randomUUID()}";val claim=LocalUbuntuElpLaunchAuthority.acquireInstaller(root)
        val forged=ElpInstallerClaim(root,claim.token)
        ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.requireNoActivity(root,forged)}
        ElpStageHealthApprovalTest.rejected{forged.releaseNeverEntered()}
        for(i in 0..8){val b=MutableList(9){true};b[i]=false;claim.release(ElpTeardownReceipt(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8]));ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString())}}
        claim.release(ElpTeardownReceipt(true,true,true,true,true,true,true,true,true));LocalUbuntuElpLaunchAuthority.requireNoActivity(root)
    }
    @Test fun normalActivityPreventsInstallerClaimWithoutBlockingOtherRoot(){
        val root="/tmp/elp-authored-install-${UUID.randomUUID()}";val activity=LocalUbuntuElpLaunchAuthority.acquireActivity(root,UUID.randomUUID().toString())
        ElpStageHealthApprovalTest.rejected{LocalUbuntuElpLaunchAuthority.acquireInstaller(root)}
        val unrelated=LocalUbuntuElpLaunchAuthority.acquireInstaller(root+"-other");unrelated.releaseNeverEntered()
        activity.release(ElpTeardownReceipt(true,true,true,true,true,true,true,true,true))
        LocalUbuntuElpLaunchAuthority.acquireInstaller(root).releaseNeverEntered()
    }

}
