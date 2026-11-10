package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCSharpHostContractTest {
    @Test fun unsupportedHostHasNoMetadataAuthority() = runBlocking {
        var starts=0
        val host=object:StdioProcessHost {
            override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command:String):String?=command
            override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess { starts++;error("generic start forbidden") }
        }
        assertNull(host.reviewedCSharpLaunchIdentity("csharp-ls","/project"))
        assertNull(host.inspectCSharpSelection("/project","/project",null))
        assertEquals(0,starts)
    }
    @Test fun privateEnvironmentDoesNotInheritRuntimeInjection() {
        val env=LspCSharpLaunchPolicy.privateEnvironment("/run/droide-csharp/owner")
        assertEquals("/tmp",env["TMPDIR"])
        assertEquals(LspCSharpLaunchPolicy.SDK_ROOT,env["PATH"])
        assertEquals("0",env["DOTNET_EnableDiagnostics_IPC"])
        for(name in listOf("NODE_OPTIONS","LD_PRELOAD","DOTNET_STARTUP_HOOKS","MSBuildSDKsPath","DOTNET_MSBUILD_SDK_RESOLVER_CLI_DIR")) assertFalse(env.containsKey(name))
    }
}
