package com.baystudio.droide.core
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class GraphqlHostForwardingTest {
    @Test fun unsupportedHostDoesNotDiscoverOrStart()=runBlocking {
        var genericStarts=0
        val host=object:StdioProcessHost {
            override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command:String):String?=error("Generic discovery forbidden")
            override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess {genericStarts++;error("Generic start forbidden")}
        }
        assertNull(host.reviewedGraphqlLaunchIdentity("droide-graphql-local-lsp","/workspace",GraphqlDeadline.start()))
        assertEquals(0,genericStarts)
    }
}
