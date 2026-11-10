package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuPowerShellEditorServicesPolicyTest {
    private val p get()=LocalUbuntuPowerShellEditorServicesPolicy
    private val definition get()=requireNotNull(LocalUbuntuVendorCatalog.find(p.FAMILY))
    private val asset get()="""{"id":${p.ASSET_ID},"name":"${p.ASSET}","size":${p.ARTIFACT_BYTES},"state":"uploaded","digest":"sha256:${p.SHA256}","browser_download_url":"${p.ASSET_URL}"}"""
    private val release get()="""{"id":${p.RELEASE_ID},"url":"${p.RELEASE_API_URL}","html_url":"${p.PROVENANCE_URL}","tag_name":"v${p.VERSION}","draft":false,"prerelease":false,"assets":[$asset]}"""
    private fun reject(action:()->Unit)=assertTrue(runCatching(action).isFailure)
    @Test fun exactPublisherIdentityIsSharedByBothPins(){
        val r=p.publisherRecipe(definition,release);p.requireReviewed(r);assertEquals("4.7.0",r.version)
        for(bad in listOf(release.replace(p.SHA256,"0".repeat(64)),release.replace(p.ASSET_ID.toString(),"${p.ASSET_ID}.0"),release.replace("[$asset]","[$asset,$asset]"),release.replace("\"draft\":false","\"draft\":false,\"draft\":false"),release.replace("\"prerelease\":false","\"prerelease\":true")))reject{p.publisherRecipe(definition,bad)}
        reject{p.requireReviewed(r.copy(version="4.8.0"))};reject{p.requireReviewed(r.copy(assetSize=null))}
    }
    private fun layout(block:(File)->Unit){val root=Files.createTempDirectory("pses-layout-").toFile();try{
        for(name in listOf(p.ENTRY,"PowerShellEditorServices/PowerShellEditorServices.psd1","LICENSE","NOTICE.txt","README.md","SECURITY.md"))File(root,name).apply{parentFile.mkdirs();writeText("authored fixture")}
        for(name in listOf("PowerShellEditorServices/bin/Common","PowerShellEditorServices/bin/Core","PSScriptAnalyzer","PSReadLine"))File(root,name).mkdirs()
        block(root)
    }finally{assertTrue(PathSecurity.deleteTreeNoFollow(root))}}
    @Test fun moduleLayoutIsDataOnlyAndRejectsUnknownRoots()=layout{root->
        val entry=File(root,p.ENTRY);p.requireTree(entry)
        File(root,"PSScriptAnalyzer/managed.dll").writeBytes(byteArrayOf(77,90,0,0));p.requireTree(entry)
        val extra=File(root,"unreviewed").apply{mkdir()};reject{p.requireTree(entry)};extra.delete()
        File(root,"LICENSE").writeText("");reject{p.requireTree(entry)}
    }
    @Test fun moduleLayoutRejectsNativeMagicAndLinks()=layout{root->
        val entry=File(root,p.ENTRY);val hidden=File(root,"PSScriptAnalyzer/disguised.txt");hidden.writeBytes(byteArrayOf(127,69,76,70));reject{p.requireTree(entry)};hidden.delete()
        Files.createSymbolicLink(hidden.toPath(),entry.toPath());reject{p.requireTree(entry)}
    }
    @Test fun directoryCountAndCancellationAreFinite()=layout{root->
        var checks=0;reject{p.requireTree(File(root,p.ENTRY)){if(++checks==6)throw kotlinx.coroutines.CancellationException("authored cancel")}};assertEquals(6,checks)
        repeat(8193){File(root,"PSScriptAnalyzer/d$it").mkdir()};reject{p.requireTree(File(root,p.ENTRY))}
    }
}
