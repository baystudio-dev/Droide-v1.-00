package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VueOwnedProcessTest {
    private fun identity():VueManagedLaunchIdentity {val root=LspVueLaunchPolicy.ROOT;val npm="$root/payload/npm";val sha="a".repeat(64);return VueManagedLaunchIdentity("lsp.vue","3.3.13","runtime.vue-helper","22.23.3",LspVueLaunchPolicy.PROFILE,sha,sha,sha,sha,sha,"vue-language-server","$root/payload/bin/vue-language-server","$npm/node_modules/@vue/language-server/bin/vue-language-server.js","$npm/node_modules/typescript-language-server/lib/cli.mjs","$npm/node_modules/typescript/lib/tsserver.js","$npm/node_modules/typescript/lib/typescript.js","$npm/node_modules/@vue/typescript-plugin/index.js",LspVueLaunchPolicy.HELPER,npm,"activation","helper-activation",sha,sha,sha)}
    private fun allocate():LocalVuePreparedOwner {val app=Files.createTempDirectory("vue-owner-test").toFile();File(app,"runtime-tmp").mkdir();val project=File(app,"workspace").apply{mkdir()};val seed=File(app,"packages/lsp.vue/3.3.13/arm64-v8a/payload/vue-health-identity.json");seed.parentFile.mkdirs();seed.writeText("{}");return LocalVuePreparedOwner.allocate(app,identity(),VueOwnerRequest(project.path,project.path,1,"a".repeat(64)))}
    private class DeadProcess:Process(){override fun getOutputStream():OutputStream=ByteArrayOutputStream();override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf());override fun getErrorStream():InputStream=ByteArrayInputStream(byteArrayOf());override fun waitFor():Int=0;override fun waitFor(timeout:Long,unit:TimeUnit)=true;override fun exitValue():Int=0;override fun destroy(){};override fun destroyForcibly():Process=this;override fun isAlive()=false}
    @Test fun unlaunchedOwnerCleanupIsCompleteAndReleasesRoot()=runBlocking {val owner=allocate();val root=owner.localRoot;assertTrue(owner.retire().complete);assertFalse(root.exists())}
    @Test fun unknownCleanupRetainsDirectoryAndBlocksReplacement()=runBlocking {val owner=allocate();val hosted=owner.createProcess({DeadProcess()},{ });val receipt=owner.retire();assertFalse(receipt.complete);assertFalse(receipt.groupEmpty);assertTrue(owner.localRoot.exists());assertTrue(runCatching{hosted.awaitExit()}.isFailure);val app=owner.localRoot.parentFile.parentFile;assertTrue(runCatching{LocalVuePreparedOwner.allocate(app,owner.identity,owner.request.copy(ownerGeneration=2))}.isFailure)}
    private class FinishingProcess : Process() {
        private val finished = CountDownLatch(1)
        fun finish() = finished.countDown()
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { finished.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("Fixture process has not exited")
            return 0
        }
        override fun destroy() = finish()
        override fun destroyForcibly(): Process = apply { finish() }
        override fun isAlive(): Boolean = finished.count != 0L
    }

    @Test fun verifiedReceiptAllowsExactCleanup() = runBlocking {
        val owner = allocate()
        val process = FinishingProcess()
        try {
            owner.createProcess({ process }, { })
            VueControlRecords.write(File(owner.localRoot, "teardown.json"),
                "{\"schema\":1,\"token\":\"${owner.token}\",\"status\":\"COMPLETE\",\"directChildReaped\":true,\"groupEmpty\":true}".toByteArray())
            process.finish()
            assertTrue(owner.retire().complete)
            assertFalse(owner.localRoot.exists())
        } finally {
            process.finish()
            owner.retire()
        }
    }
    @Test fun selectionGuardReadsOnlyBoundAtomicState()=runBlocking {val owner=allocate();var current=true;owner.bindSelectionCurrentness{current};assertTrue(owner.isCurrent);current=false;assertFalse(owner.isCurrent);owner.retire();Unit}
    @Test fun mismatchedReceiptCannotConfirmCleanup()=runBlocking {val owner=allocate();owner.createProcess({DeadProcess()},{ });VueControlRecords.write(File(owner.localRoot,"teardown.json"),"{\"schema\":1,\"token\":\"wrong\",\"status\":\"COMPLETE\",\"directChildReaped\":true,\"groupEmpty\":true}".toByteArray());assertFalse(owner.retire().complete);assertTrue(owner.localRoot.exists())}
}
