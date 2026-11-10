package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class GraphqlHealthTest {
    @Test fun printedSuccessCannotCreateCleanup(){val r=ExecResult(0,LocalUbuntuGraphqlLspHealth.SUCCESS+"\n"+LocalUbuntuGraphqlLspHealth.CLEANUP,false);assertFalse(LocalUbuntuGraphqlLspHealth.acceptsServer(r));assertNull(LocalUbuntuGraphqlLspHealth.teardownReceipt(r))}
    @Test fun printedRuntimeSuccessCannotCreateCleanup(){val r=ExecResult(0,LocalUbuntuGraphqlLspHealth.NODE_SUCCESS+"\n"+LocalUbuntuGraphqlLspHealth.CLEANUP,false);assertFalse(LocalUbuntuGraphqlLspHealth.acceptsRuntime(r))}
    @Test fun failedHealthCannotBeHealthy(){assertFalse(LocalUbuntuGraphqlLspHealth.acceptsServer(ExecResult(70,"",false)));assertFalse(LocalUbuntuGraphqlLspHealth.acceptsRuntime(ExecResult(124,"",true)))}
}
