package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NimOwnedProcessTest {
    private fun fixture(): Triple<File,NimManagedLaunchIdentity,NimOwnerRequest> {
        val app=Files.createTempDirectory("nim-owner-fixture-").toFile();File(app,"runtime-tmp").mkdir()
        val project=File(app,"workspace").apply {mkdir()};File(project,"sample.nimble").writeText("# authored inert metadata fixture\n");File(project,"main.nim").writeText("# authored inert file\n")
        val s="/opt/droide/packages/lsp.nimlangserver/1.16.0/arm64-v8a";val t="/opt/droide/packages/runtime.nim-lsp/2.2.12/arm64-v8a/payload/tree/nim-2.2.12/bin"
        val hash="a".repeat(64);val activation="00000000-0000-0000-0000-000000000000"
        val id=NimManagedLaunchIdentity("lsp.nimlangserver","1.16.0","runtime.nim-lsp","2.2.12","runtime.nim-helper","22.23.3",LspNimLaunchPolicy.POLICY_REVISION,
            "024a8b3e6d8d1f7c35694048704754d3d660b520f9a0331822f5a579a964925e","3dc04191af72a8310cf1c8ffb6f772cc3716fc5f264bc7c453ab4aecb4085221","5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2",
            hash,hash,hash,hash,hash,hash,hash,"$s/payload/bin/nimlangserver","$s/payload/bin/nimlangserver","$s/payload/tree/nimlangserver","$t/nim","$t/nimsuggest","$t/nimble",LspNimLaunchPolicy.HELPER,
            activation,activation,activation,hash,"cbe2acd9df03cee1f27aa02d4aabc938f4cb66b2e437c3359c398298b469b4c0",hash)
        for(guest in listOf(id.serverExecutable,id.compilerExecutable,id.backendExecutable,id.nimbleExecutable,id.helperExecutable)+listOf(LspNimLaunchPolicy.SUPERVISOR,LspNimLaunchPolicy.ADAPTER,LspNimLaunchPolicy.HEALTH,LspNimLaunchPolicy.HELPER_HEALTH).map {"$s/$it"}+
            listOf("lsp.nimlangserver" to "1.16.0","runtime.nim-lsp" to "2.2.12","runtime.nim-helper" to "22.23.3").flatMap {(f,v)->listOf("DROIDE_ACTIVATION_ID","NIM_AUTHORITY.json","NIM_CLOSURE.json","SHA256SUMS","TREE_SHA256SUMS").map {"/opt/droide/packages/$f/$v/arm64-v8a/$it"}}) {
            val target=File(app,"packages/"+guest.removePrefix("/opt/droide/packages/"));target.parentFile!!.mkdirs();target.writeText("authored inert proof fixture\n")
        }
        return Triple(app,id,NimOwnerRequest(project.canonicalPath,project.canonicalPath,7,hash))
    }
    @Test fun preparedOwnerIsImmutableRevocableAndCleanableWithoutProcess():Unit=runBlocking {
        val (app,id,request)=fixture();val owner=LocalNimPreparedOwner.allocate(app,id,request)
        assertTrue(owner.isCurrent);assertEquals(7L,owner.request.ownerGeneration);assertEquals(32,owner.token.length)
        val bytes=File(owner.localRoot,"owner.json").readBytes();assertTrue(bytes.size<=16384);owner.verifyPrivate();owner.verifyProject()
        owner.close();assertFalse(owner.isCurrent);assertTrue(owner.retire().complete);assertFalse(owner.localRoot.exists());app.deleteRecursively()
    }
    @Test fun changedManifestInvalidatesPreparedObservation():Unit=runBlocking {
        val (app,id,request)=fixture();val owner=LocalNimPreparedOwner.allocate(app,id,request)
        File(request.projectLocalCanonical,"sample.nimble").appendText("# changed\n")
        assertTrue(runCatching {owner.verifyProject()}.isFailure);owner.close();assertTrue(owner.retire().complete);app.deleteRecursively()
    }
    @Test fun duplicateOrSymlinkManifestRejectsBeforeProcess() {
        val (app,id,request)=fixture();File(request.projectLocalCanonical,"second.nimble").writeText("inert")
        assertTrue(runCatching {LocalNimPreparedOwner.allocate(app,id,request)}.isFailure);app.deleteRecursively()
    }
    @Test fun strictControlJsonRejectsDuplicateKeysDepthAndTrailingText() {
        assertTrue(runCatching {NimControlRecords.strict("{\"token\":\"a\",\"token\":\"b\"}".toByteArray())}.isFailure)
        assertTrue(runCatching {NimControlRecords.strict("{\"n\":{\"n\":1,\"n\":2}}".toByteArray())}.isFailure)
        assertTrue(runCatching {NimControlRecords.strict("{}{}".toByteArray())}.isFailure)
        assertTrue(runCatching {NimControlRecords.strict("{\u000b\"a\":1}".toByteArray())}.isFailure)
        assertEquals(1,NimControlRecords.strict("{\"a\":1}".toByteArray()).size)
    }
    @Test fun terminalOrInjectedHelperIdentityNeverMatches() {
        val (app,id,_)=fixture();assertTrue(LspNimLaunchPolicy.matchesIdentity(id))
        assertFalse(LspNimLaunchPolicy.matchesIdentity(id.copy(helperExecutable="/usr/bin/node")))
        assertFalse(LspNimLaunchPolicy.matchesIdentity(id.copy(helperFamilyId="runtime.node")))
        assertFalse(LspNimLaunchPolicy.matchesIdentity(id.copy(compilerExecutable="/tmp/nimbinaries/nim")));app.deleteRecursively()
    }
    @Test fun healthCommandHasEmptyPathAndOriginalPrivateHelperOnly() {
        val (app,id,_)=fixture();val argv=LocalUbuntuNimLspHealth.command(LspNimLaunchPolicy.packageRoot(id),id)
        assertTrue(argv.contains("PATH="));assertTrue(argv.contains(LspNimLaunchPolicy.HELPER));assertFalse(argv.contains("nimble"));assertFalse(argv.contains("/usr/bin/node"));app.deleteRecursively()
    }
    @Test fun statusChannelsRejectHardLinksSymlinksAndNonPrivateModes() {
        val root=Files.createTempDirectory("nim-status-fixture-").toFile();val status=File(root,"status.json");NimControlRecords.write(status,"{}".toByteArray())
        assertTrue(NimControlRecords.read(status).isEmpty())
        val alias=File(root,"alias");Files.createLink(alias.toPath(),status.toPath());assertTrue(runCatching {NimControlRecords.read(status)}.isFailure);alias.delete()
        Files.createSymbolicLink(alias.toPath(),status.toPath());assertTrue(runCatching {NimControlRecords.read(alias)}.isFailure);alias.delete()
        Files.setPosixFilePermissions(status.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));assertTrue(runCatching {NimControlRecords.read(status)}.isFailure);root.deleteRecursively()
    }
    @Test fun safeFailuresAreClosedLocalVocabulary() {
        assertTrue(NimAdmissionException.fromKind("COMPILER_CONFLICT").safeMessage.contains("2.2.12"))
        assertFalse(NimAdmissionException.fromKind("untrusted project stderr").safeMessage.contains("untrusted"))
    }
}
