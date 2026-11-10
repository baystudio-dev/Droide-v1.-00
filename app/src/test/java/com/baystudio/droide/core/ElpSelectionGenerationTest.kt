package com.baystudio.droide.core
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class ElpSelectionGenerationTest {
    private fun record()=ManagedPackageRecord(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION,ExecutionScope.LOCAL_LINUX_ARM64.name,"/app/packages/elp-fixture",1)
    @Test fun unrelatedTerminalSelectionsAndNoOpsPreserveElp(){val p=WorkspaceToolchainPreferences(RuntimeEnvironment.getApplication(),File("/elp-${UUID.randomUUID()}"));val t=p.elpSelectionGeneration();p.clear(LocalUbuntuElpPolicy.FAMILY);p.set("runtime.node","22.23.3");p.set("runtime.erlang","27.3");assertTrue(p.isElpSelectionGenerationCurrent(t));val g=p.graphqlSelectionGeneration();p.set(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION);assertFalse(p.isElpSelectionGenerationCurrent(t));assertTrue(p.isGraphqlSelectionGenerationCurrent(g));val n=p.elpSelectionGeneration();p.set(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION);assertTrue(p.isElpSelectionGenerationCurrent(n))}
    @Test fun staleSelectionCannotEnterFactory(){val p=WorkspaceToolchainPreferences(RuntimeEnvironment.getApplication(),File("/elp-${UUID.randomUUID()}"));val t=p.elpSelectionGeneration();p.set(LocalUbuntuElpPolicy.FAMILY,"different");var entered=false;assertThrows(IllegalStateException::class.java){p.withElpSelectionGeneration(t){entered=true}};assertFalse(entered)}
    @Test fun directRemovalRevokesElpGeneration(){val r=ManagedPackageRegistry(RuntimeEnvironment.getApplication());val before=r.list();val a=record();r.put(a);val t=r.elpPackageGeneration();r.remove(a.familyId,a.version);assertFalse("Direct ELP removal must revoke the launch generation",r.isElpPackageGenerationCurrent(t));r.replaceAll(before)}
    @Test fun heldActivityBlocksRemovalButNotUnrelatedNoOp(){val app=RuntimeEnvironment.getApplication();val r=ManagedPackageRegistry(app);val before=r.list();val a=record();r.put(a);val h=LocalUbuntuElpLaunchAuthority.acquireActivity(app.filesDir.canonicalPath,UUID.randomUUID().toString());try{r.remove("unrelated","missing");assertThrows(IllegalStateException::class.java){r.remove(a.familyId,a.version)};assertNotNull(r.find(a.familyId,a.version))}finally{h.release(ElpTeardownReceipt(true,true,true,true,true,true,true,true,true));r.replaceAll(before)}}
    @Test fun otherFamiliesRetainTheirGenerationDefaults(){val r=ManagedPackageRegistry(RuntimeEnvironment.getApplication());val before=r.list();val gql=r.graphqlPackageGeneration();val toml=r.tomlPackageGeneration();val vue=r.vuePackageGeneration();r.put(record());assertTrue(r.isGraphqlPackageGenerationCurrent(gql));assertTrue(r.isTomlPackageGenerationCurrent(toml));assertTrue(r.isVuePackageGenerationCurrent(vue));r.replaceAll(before)}
    @Test fun installerTokenAllowsOnlyItsOwnElpPublication(){
        val app=RuntimeEnvironment.getApplication();val r=ManagedPackageRegistry(app);val before=r.list();val a=record();r.put(a)
        val claim=LocalUbuntuElpLaunchAuthority.acquireInstaller(app.filesDir.canonicalPath)
        try {
            val token=r.elpPackageGeneration();val updated=a.copy(installedAtEpochMs=2)
            assertThrows(IllegalStateException::class.java){r.put(updated)}
            assertThrows(IllegalStateException::class.java){r.remove(a.familyId,a.version)}
            r.replaceAll(r.list());assertTrue(r.isElpPackageGenerationCurrent(token))
            val next=r.list().filterNot{it.familyId==a.familyId&&it.version==a.version}+updated
            val forged=ElpInstallerClaim(app.filesDir.canonicalPath,claim.token)
            assertThrows(IllegalStateException::class.java){r.replaceAllElp(next,forged)}
            assertEquals(a,r.find(a.familyId,a.version))
            r.replaceAllElp(next,claim);assertEquals(updated,r.find(a.familyId,a.version));assertFalse(r.isElpPackageGenerationCurrent(token))
            assertThrows(IllegalStateException::class.java){LocalUbuntuElpLaunchAuthority.acquireActivity(app.filesDir.canonicalPath,UUID.randomUUID().toString())}
        } finally {claim.releaseNeverEntered();r.replaceAll(before)}
    }
    @Test fun unrelatedRootInstallerCannotAuthorizeElpChange(){
        val app=RuntimeEnvironment.getApplication();val r=ManagedPackageRegistry(app);val before=r.list();val claim=LocalUbuntuElpLaunchAuthority.acquireInstaller(app.filesDir.canonicalPath+"-other")
        try {assertThrows(IllegalStateException::class.java){r.replaceAllElp(before.filterNot{it.familyId==LocalUbuntuElpPolicy.FAMILY}+record(),claim)}}
        finally {claim.releaseNeverEntered();r.replaceAll(before)}
    }

}
