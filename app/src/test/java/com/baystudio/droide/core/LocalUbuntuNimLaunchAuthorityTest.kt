package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuNimLaunchAuthorityTest {
    @Test fun incompleteReceiptDiscoveryReturnsNullWithoutHealthOrNetwork()=runBlocking {
        val root=Files.createTempDirectory("nim-authority").toFile()
        try {
            val r=ManagedPackageRecord(LocalUbuntuNimPolicy.FAMILY,LocalUbuntuNimPolicy.VERSION,ExecutionScope.LOCAL_LINUX_ARM64.name,
                File(root,"packages/lsp.nimlangserver/1.16.0/arm64-v8a").path,0,active=true)
            assertNull(LocalUbuntuNimLaunchAuthority.inspect(root.path,r,listOf(r),emptyMap(),"/opt/droide/bin/nimlangserver"))
            assertNull(LocalUbuntuNimLaunchAuthority.inspect(root.path,r,listOf(r,r),emptyMap(),"/opt/droide/bin/nimlangserver"))
        } finally{root.deleteRecursively()}
    }
    @Test fun dependencyFamilyCannotBeTerminalRuntime() {
        val root=Files.createTempDirectory("nim-family").toFile()
        try { val r=ManagedPackageRecord("runtime.node","22.23.3",ExecutionScope.LOCAL_LINUX_ARM64.name,root.path,0)
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.requireDependency(r,LocalUbuntuNimHelperPolicy.FAMILY,root.path)}.isFailure)
        } finally{root.deleteRecursively()}
    }
}
