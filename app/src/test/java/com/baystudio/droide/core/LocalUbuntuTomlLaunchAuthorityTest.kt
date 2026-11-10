package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuTomlLaunchAuthorityTest {
 @Test fun helpersAreExactAndIndependent(){assertEquals(setOf("payload/toml-adapter.cjs","payload/toml-health.cjs","payload/toml-node-health.cjs"),LocalUbuntuTomlLaunchAuthority.expectedHelperHashes.keys);assertTrue(LocalUbuntuTomlLaunchAuthority.expectedHelperHashes.values.all{it.matches(Regex("[0-9a-f]{64}"))});assertTrue(LocalUbuntuTomlLaunchAuthority.guestWrapper("lsp.taplo-wasm",LspTomlLaunchPolicy.ROOT).contains("exit 64"))}
 @Test fun privateEnvironmentDoesNotInheritNodeOrProjectInputs(){val e=LspTomlLaunchPolicy.privateEnvironment("/run/droide-toml/test");assertEquals("/run/droide-toml/test/tmp",e["TMPDIR"]);assertFalse(e.containsKey("NODE_OPTIONS"));assertFalse(e.containsKey("NODE_PATH"));assertEquals(LspTomlLaunchPolicy.NODE.substringBeforeLast('/'),e["PATH"])}
 @Test fun stdoutSuccessCannotForgeCleanup(){val result=ExecResult(0,LocalUbuntuTomlLspHealth.SUCCESS+"\n",false);assertFalse(LocalUbuntuTomlLspHealth.succeeded(result));assertTrue(LocalUbuntuTomlLspHealth.succeeded(result.copy(output=result.output+LocalUbuntuTomlLspHealth.CLEANUP+"\n")));assertFalse(LocalUbuntuTomlLspHealth.succeeded(result.copy(timedOut=true)))}
 @Test fun cleanupAndHealthTimeoutAreSeparate(){val r=ExecResult(124,LocalUbuntuTomlLspHealth.CLEANUP+"\n",true);assertTrue(LocalUbuntuTomlLspHealth.cleanupVerified(r));assertFalse(LocalUbuntuTomlLspHealth.succeeded(r))}
}
