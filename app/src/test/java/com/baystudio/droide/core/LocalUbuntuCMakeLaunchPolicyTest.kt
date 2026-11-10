package com.baystudio.droide.core

import android.app.Application
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class LocalUbuntuCMakeLaunchPolicyTest {
    @After fun resetOwnedHostBoundary() = CMakeHostOs.reset()

    @Test fun editorEnvironmentIsPrivateFiniteAndHealthHasNoSearchingPath() = runBlocking<Unit> {
        val app=CMakeHostOs.temporaryRoot("cmake-env")
        try {
            val state=CMakePrivateState.allocate(app,"environment");val identity=cmakeIdentityFixture()
            val environment=LocalUbuntuCMakeLaunchPolicy.environment(identity,state)
            assertEquals("/usr/bin:/bin",environment["PATH"])
            assertEquals(state.guest("home"),environment["HOME"])
            assertFalse(environment.keys.any{it.startsWith("PYTHON")||it.startsWith("LD_")||it=="VIRTUAL_ENV"})
            assertEquals("",LocalUbuntuCMakeLaunchPolicy.environment(identity,state,true)["PATH"])
            assertThrows(IllegalArgumentException::class.java){LocalUbuntuCMakeLaunchPolicy.environment(identity.copy(approvedToolBinGuestPaths=listOf("relative/bin")),state)}
            state.cleanup(true)
        }finally{app.deleteRecursively()}
    }
    @Test fun consentFingerprintIncludesRuntimeHelpersAndPolicies() {
        val identity=cmakeIdentityFixture()
        assertEquals(identity.consentIdentity,identity.copy().consentIdentity)
        assertNotEquals(identity.consentIdentity,identity.copy(runtimeFingerprint="replaced").consentIdentity)
        assertNotEquals(identity.consentIdentity,identity.copy(supervisorSha256="replaced").consentIdentity)
        assertNotEquals(identity.consentIdentity,identity.copy(cmakeExecutableGuestAbsolute="/other/cmake").consentIdentity)
    }
}
