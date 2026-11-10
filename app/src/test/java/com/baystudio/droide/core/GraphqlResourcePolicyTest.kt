package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class GraphqlResourcePolicyTest {
    @Test fun exactPrivateLimits(){val p=GraphqlResourcePolicy.ANALYSIS;assertEquals(600,p.cpuSeconds);assertEquals(60,GraphqlResourcePolicy.HEALTH.cpuSeconds);assertEquals(16_777_216L,p.addressSpaceKiB);assertEquals(67_108_864L,p.fileSizeBytes);assertEquals(256,p.openFiles);assertEquals(256,p.realUidTasks);assertEquals(listOf("--max-old-space-size=256"),p.nodeArguments)}
    @Test fun fullShellChain(){val p=GraphqlResourcePolicy.HEALTH.shellPrefix();assertEquals("/bin/bash",p[0]);assertTrue(p[2].contains("/usr/bin/env -u PWD -u SHLVL -u _"));for(k in listOf("c","t","f","n","u","v")){assertTrue(p[2].contains("ulimit -S$k"));assertTrue(p[2].contains("ulimit -H$k"))}}
    @Test fun privateEnvironmentExact(){val e=LspGraphqlLaunchPolicy.privateEnvironment("/run/droide-graphql/droide-graphql-"+"a".repeat(32));assertEquals(setOf("HOME","TMPDIR","XDG_CONFIG_HOME","XDG_CACHE_HOME","XDG_DATA_HOME","XDG_STATE_HOME","XDG_RUNTIME_DIR","PATH","LANG","LC_ALL","TERM"),e.keys);assertEquals(LspGraphqlLaunchPolicy.NODE.substringBeforeLast('/'),e["PATH"]);assertFalse(e.containsKey("NODE_OPTIONS"));assertFalse(e.containsKey("NODE_PATH"))}
    @Test fun unsafeManagedPathsReject(){assertFalse(LspGraphqlLaunchPolicy.safeManagedPath("/x/../y"));assertFalse(LspGraphqlLaunchPolicy.safeManagedPath("/x a"));assertFalse(LspGraphqlLaunchPolicy.safeManagedPath("/x;bad"))}
}
