package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual production APIs with authored host SharedPreferences boundary in runtime validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VueSelectionGenerationTest {
    private fun context():Context=object:ContextWrapper(RuntimeEnvironment.getApplication()){private val prefix="vue-test-"+java.util.UUID.randomUUID()+"-";override fun getApplicationContext():Context=this;override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)}
    private fun record(family:String,version:String="1")=ManagedPackageRecord(family,version,ExecutionScope.LOCAL_LINUX_ARM64.name,"/owned/$family/$version",1L)
    @Test fun preferenceRelevantChangesInvalidateBeforeMutationReturns(){val context=context();val p=WorkspaceToolchainPreferences(context,File("/test/vue"));val t=p.vueSelectionGeneration();p.set("lsp.vue","3.3.13");assertFalse(p.isVueSelectionGenerationCurrent(t));val n=p.vueSelectionGeneration();p.set("lsp.vue","3.3.13");assertTrue(p.isVueSelectionGenerationCurrent(n));p.set("runtime.node","22");assertTrue(p.isVueSelectionGenerationCurrent(n));p.clear("runtime.node");assertTrue(p.isVueSelectionGenerationCurrent(n));p.clear("lsp.vue");assertFalse(p.isVueSelectionGenerationCurrent(n))}
    @Test fun preferencePruneNoOpAndHelperSelection(){val p=WorkspaceToolchainPreferences(context(),File("/test/prune"));p.set("lsp.vue","3.3.13");val t=p.vueSelectionGeneration();p.prune(setOf("lsp.vue" to "3.3.13"));assertTrue(p.isVueSelectionGenerationCurrent(t));p.set("runtime.vue-helper","22.23.3");assertFalse(p.isVueSelectionGenerationCurrent(t));val n=p.vueSelectionGeneration();p.prune(setOf("lsp.vue" to "3.3.13"));assertFalse(p.isVueSelectionGenerationCurrent(n))}
    @Test fun deniedPreferenceAdmissionNeverInvalidates(){val p=WorkspaceToolchainPreferences(context(),File("/test/denied")){throw IllegalStateException("cancelled")};val t=p.vueSelectionGeneration();assertThrows(IllegalStateException::class.java){p.set("lsp.vue","3.3.13")};assertTrue(p.isVueSelectionGenerationCurrent(t))}
    @Test fun registryRelevantReceiptChangesInvalidateAndNoOpsPreserve(){val r=ManagedPackageRegistry(context());val a=record("lsp.vue","3.3.13");val t=r.vuePackageGeneration();r.put(a);assertFalse(r.isVuePackageGenerationCurrent(t));val n=r.vuePackageGeneration();r.put(a);assertTrue(r.isVuePackageGenerationCurrent(n));r.put(record("unrelated"));assertTrue(r.isVuePackageGenerationCurrent(n));r.replaceAll(r.list().reversed());assertTrue(r.isVuePackageGenerationCurrent(n));r.put(a.copy(metadata=mapOf("generation" to "changed")));assertFalse(r.isVuePackageGenerationCurrent(n))}
    @Test fun registryRemoveAndHelperReplacementsInvalidate(){val r=ManagedPackageRegistry(context());val a=record("runtime.vue-helper","22.23.3");r.put(a);val t=r.vuePackageGeneration();r.remove("unrelated","1");assertTrue(r.isVuePackageGenerationCurrent(t));r.remove(a.familyId,a.version);assertFalse(r.isVuePackageGenerationCurrent(t))}
    @Test fun pureTokensDoNotAcquireMutationAdmissionOrPreferenceLock(){val entered=CountDownLatch(1);val release=CountDownLatch(1);val context=context();val p=WorkspaceToolchainPreferences(context,File("/test/lock")){action->entered.countDown();check(release.await(3,TimeUnit.SECONDS));action()};val t=p.vueSelectionGeneration();val worker=Thread{p.set("lsp.vue","3.3.13")};worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));val start=System.nanoTime();assertTrue(p.isVueSelectionGenerationCurrent(t));assertTrue(System.nanoTime()-start<100_000_000L);release.countDown();worker.join(3000);assertFalse(worker.isAlive);assertFalse(p.isVueSelectionGenerationCurrent(t))}
    @Test fun finalCreationTokenGateDoesNotReadStoredState() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val base = context()
        val guarded = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val store = base.getSharedPreferences(name, mode)
                return object : SharedPreferences by store {
                    override fun getString(key: String, defValue: String?): String? { reads.incrementAndGet();return store.getString(key,defValue) }
                }
            }
        }
        val p = WorkspaceToolchainPreferences(guarded, File("/test/pure-final"))
        val r = ManagedPackageRegistry(guarded)
        val pt = p.vueSelectionGeneration();val rt = r.vuePackageGeneration()
        reads.set(0)
        var created = false
        p.withVueSelectionGeneration(pt) { VueGenerationAdmission.withAdmission {
            check(p.isVueSelectionGenerationCurrent(pt) && r.isVuePackageGenerationCurrent(rt));created = true
        } }
        assertTrue(created);assertEquals(0,reads.get())
        p.set("runtime.vue-helper", "unsupported")
        assertThrows(IllegalStateException::class.java) { p.withVueSelectionGeneration(pt) { error("factory reached") } }
    }
    @Test fun heldCrossInstanceRegistryPublicationCannotAdmitMidPublicationToken() {
        val entered = CountDownLatch(1);val release = CountDownLatch(1)
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        val base = context()
        val guarded = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val store = base.getSharedPreferences(name,mode)
                return object : SharedPreferences by store {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = store.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key,value);return this }
                            override fun commit(): Boolean {
                                if (hold.get()) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val first=ManagedPackageRegistry(guarded);val second=ManagedPackageRegistry(guarded)
        val old=record("lsp.vue","3.3.13");first.put(old)
        val before=second.vuePackageGeneration();hold.set(true)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer=Thread { try { first.put(old.copy(metadata=mapOf("generation" to "new"))) } catch(t:Throwable){failure.set(t)} }
        writer.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val during=second.vuePackageGeneration();assertFalse(second.isVuePackageGenerationCurrent(before))
        assertEquals(old,second.list().single())
        val attempted=CountDownLatch(1);val finished=CountDownLatch(1);val created=java.util.concurrent.atomic.AtomicBoolean(false)
        val admission=Thread { attempted.countDown();VueGenerationAdmission.withAdmission { if(second.isVuePackageGenerationCurrent(during))created.set(true) };finished.countDown() }
        admission.start();assertTrue(attempted.await(5,TimeUnit.SECONDS));assertFalse(finished.await(50,TimeUnit.MILLISECONDS))
        release.countDown();writer.join(5000);admission.join(5000)
        assertFalse(writer.isAlive);assertFalse(admission.isAlive);assertNull(failure.get())
        assertFalse(created.get());assertFalse(second.isVuePackageGenerationCurrent(during))
        assertEquals("new",second.list().single().metadata["generation"])
    }
    @Test fun capturedBeforeProofSelectionCannotBindAfterChange() {
        val base=context();val p=WorkspaceToolchainPreferences(base,File("/test/proof"));p.set("lsp.vue","3.3.13")
        val before=p.vueSelectionGeneration();val observed=p.selections()
        val other=WorkspaceToolchainPreferences(base,File("/test/proof"));other.set("lsp.vue","unsupported")
        assertEquals("3.3.13",observed["lsp.vue"])
        assertFalse(p.isVueSelectionGenerationCurrent(before))
        assertThrows(IllegalStateException::class.java) { p.withVueSelectionGeneration(before) { error("old identity admitted") } }
    }

}
