package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuVueLaunchAuthorityTest {
    @Test fun guestWrappersExposeOnlyExactHealthSelector(){for(family in listOf(LocalUbuntuVuePolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY)){val root=if(family==LocalUbuntuVuePolicy.FAMILY)LocalUbuntuVuePolicy.ROOT else LocalUbuntuVueHelperPolicy.ROOT;val value=LocalUbuntuVueLaunchAuthority.guestWrapper(family,root);assertTrue(value.contains("= --health ]"));assertTrue(value.contains("-eq 1 ]"));assertTrue(value.contains("exit 64"));assertTrue(value.contains("/usr/bin/env"));assertFalse(value.contains("exec node "));assertFalse(value.contains("\"\$@\""))}}
    @Test fun healthReceiptRequiresSuccessfulCleanupAndNoTimeout(){val cleanup=LocalUbuntuVueLspHealth.CLEANUP;val success=LocalUbuntuVueLspHealth.SUCCESS;assertTrue(LocalUbuntuVueLspHealth.succeeded(ExecResult(0,"$cleanup\n$success\n",false)));assertFalse(LocalUbuntuVueLspHealth.succeeded(ExecResult(0,"$success\n",false)));assertFalse(LocalUbuntuVueLspHealth.cleanupVerified(ExecResult(0,"$cleanup\n",timedOut=true)));assertFalse(LocalUbuntuVueLspHealth.cleanupVerified(ExecResult(0,"$cleanup\n",timedOut=false,outputIncomplete=true)));assertFalse(LocalUbuntuVueLspHealth.cleanupVerified(ExecResult(0,"$cleanup\n$cleanup\n",false)))}
    @Test fun helperHealthReceiptRequiresIdentityAndCleanup(){val result=ExecResult(0,"${LocalUbuntuVueLspHealth.HELPER_SUCCESS}\n${LocalUbuntuVueLspHealth.CLEANUP}\n",false);assertTrue(LocalUbuntuVueLspHealth.helperSucceeded(result));assertFalse(LocalUbuntuVueLspHealth.helperSucceeded(result.copy(output=LocalUbuntuVueLspHealth.HELPER_SUCCESS)))}
}
