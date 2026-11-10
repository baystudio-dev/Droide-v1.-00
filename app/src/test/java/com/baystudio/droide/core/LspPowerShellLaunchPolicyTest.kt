package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspPowerShellLaunchPolicyTest {
    private fun recipe()=LocalUbuntuVendorRecipe(requireNotNull(LocalUbuntuVendorCatalog.find(LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)),"4.7.0","v4.7.0",LocalUbuntuPowerShellEditorServicesPolicy.ASSET,LocalUbuntuPowerShellEditorServicesPolicy.ASSET_URL,LocalUbuntuPowerShellEditorServicesPolicy.SHA256,LocalUbuntuPowerShellEditorServicesPolicy.ARTIFACT_BYTES,LocalUbuntuPowerShellEditorServicesPolicy.PROVENANCE_URL)
    @Test fun supplementaryNoticeMatchesSealedAsset(){
        val root = java.io.File(System.getProperty("powershell.source", "."))
        val asset = listOf(java.io.File(root,"app/src/main/assets/legal/licenses/PowerShell-7.6.7-PSES-4.7.0-RECONCILIATION.txt"),java.io.File(root,"src/main/assets/legal/licenses/PowerShell-7.6.7-PSES-4.7.0-RECONCILIATION.txt")).first { it.isFile }
        assertEquals(asset.readText(),LocalUbuntuPowerShellLaunchAuthority.noticeText)
        assertEquals(LocalUbuntuPowerShellLaunchAuthority.NOTICE_SHA256,LspPowerShellLaunchPolicy.sha256(LocalUbuntuPowerShellLaunchAuthority.noticeText))
    }
    @Test fun allManagedPolicyPathsChooseOwnedSupervisor(){
        val root="/opt/droide/packages/lsp.powershell-editor-services/4.7.0/arm64-v8a";val r=recipe();val target="$root/payload/tree/${r.entryPoint}"
        for(argv in listOf(LocalUbuntuVendorPolicy.argv(r,target),LocalUbuntuVendorPolicy.healthCommand(r,target),LocalUbuntuVendorPolicy.lspProbe(r,root,target))){
            assertEquals("/bin/sh",argv.first());assertTrue(argv.contains("/usr/bin/node"));assertTrue(argv.contains("$root/powershell-supervisor.cjs"));assertFalse(argv.contains(LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST));assertFalse(argv.contains(target))
        }
        assertTrue(LocalUbuntuVendorPolicy.argv(r,target).contains("editor"));assertTrue(LocalUbuntuVendorPolicy.healthCommand(r,target).contains("health"))
        assertTrue(runCatching{LocalUbuntuVendorPolicy.argv(r,"/tmp/Start-EditorServices.ps1")}.isFailure)
    }
    @Test fun preNodeHandoffIsSanitizedAndHealthDoesNotPreserveEditorContext(){
        val health=LspPowerShellLaunchPolicy.handoff(true);val editor=LspPowerShellLaunchPolicy.handoff(false)
        for(value in listOf(health,editor)){assertTrue(value.contains("exec /usr/bin/env -i"));assertTrue(value.contains("POWERSHELL_TELEMETRY_OPTOUT=1"));assertTrue(value.contains("DOTNET_EnableDiagnostics=0"));assertFalse(value.contains("NODE_OPTIONS"));assertFalse(value.contains("LD_PRELOAD"))}
        assertTrue(health.contains("HOME=/nonexistent PATH="));assertFalse(health.contains("PSModulePath"));assertTrue(editor.contains("PSModulePath"))
        assertTrue(LspPowerShellLaunchPolicy.editorIdentity.contains(LspPowerShellLaunchPolicy.sha256(LocalUbuntuPowerShellLspHealth.script)))
    }
    @Test fun wrapperHasOnlyZeroArgumentEditorOrSoleHealthSelector(){
        val r=recipe();val text=LspPowerShellLaunchPolicy.guestWrapper(r,"/unused","/managed/root")
        assertTrue(text.contains("[ \"\$#\" -eq 1 ]"));assertTrue(text.contains("--droide-powershell-health-v1"));assertTrue(text.contains("[ \"\$#\" -eq 0 ]"));assertFalse(text.contains("-Stdio"));assertFalse(text.contains("ulimit -v"))
    }
}
