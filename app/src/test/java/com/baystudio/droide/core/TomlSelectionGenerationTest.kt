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
class TomlSelectionGenerationTest {
    private fun context():Context=object:ContextWrapper(RuntimeEnvironment.getApplication()){private val prefix="toml-test-"+java.util.UUID.randomUUID()+"-";override fun getApplicationContext():Context=this;override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)}
    private fun record(family:String,version:String="1")=ManagedPackageRecord(family,version,ExecutionScope.LOCAL_LINUX_ARM64.name,"/owned/$family/$version",1L)
    @Test fun preferenceRelevantChangesInvalidateBeforeMutationReturns(){val context=context();val p=WorkspaceToolchainPreferences(context,File("/test/toml"));val t=p.tomlSelectionGeneration();p.set("lsp.taplo-wasm","0.8.0");assertFalse(p.isTomlSelectionGenerationCurrent(t));val n=p.tomlSelectionGeneration();p.set("lsp.taplo-wasm","0.8.0");assertTrue(p.isTomlSelectionGenerationCurrent(n));p.set("runtime.node","22");assertTrue(p.isTomlSelectionGenerationCurrent(n));p.clear("runtime.node");assertTrue(p.isTomlSelectionGenerationCurrent(n));p.clear("lsp.taplo-wasm");assertFalse(p.isTomlSelectionGenerationCurrent(n))}
    @Test fun preferencePruneNoOpAndHelperSelection(){val p=WorkspaceToolchainPreferences(context(),File("/test/prune"));p.set("lsp.taplo-wasm","0.8.0");val t=p.tomlSelectionGeneration();p.prune(setOf("lsp.taplo-wasm" to "0.8.0"));assertTrue(p.isTomlSelectionGenerationCurrent(t));p.set("runtime.taplo-node","22.23.3");assertFalse(p.isTomlSelectionGenerationCurrent(t));val n=p.tomlSelectionGeneration();p.prune(setOf("lsp.taplo-wasm" to "0.8.0"));assertFalse(p.isTomlSelectionGenerationCurrent(n))}
    @Test fun deniedPreferenceAdmissionNeverInvalidates(){val p=WorkspaceToolchainPreferences(context(),File("/test/denied")){throw IllegalStateException("cancelled")};val t=p.tomlSelectionGeneration();assertThrows(IllegalStateException::class.java){p.set("lsp.taplo-wasm","0.8.0")};assertTrue(p.isTomlSelectionGenerationCurrent(t))}
    @Test fun registryRelevantReceiptChangesInvalidateAndNoOpsPreserve(){val r=ManagedPackageRegistry(context());val a=record("lsp.taplo-wasm","0.8.0");val t=r.tomlPackageGeneration();r.put(a);assertFalse(r.isTomlPackageGenerationCurrent(t));val n=r.tomlPackageGeneration();r.put(a);assertTrue(r.isTomlPackageGenerationCurrent(n));r.put(record("unrelated"));assertTrue(r.isTomlPackageGenerationCurrent(n));r.replaceAll(r.list().reversed());assertTrue(r.isTomlPackageGenerationCurrent(n));r.put(a.copy(metadata=mapOf("generation" to "changed")));assertFalse(r.isTomlPackageGenerationCurrent(n))}
    @Test fun registryRemoveAndHelperReplacementsInvalidate(){val r=ManagedPackageRegistry(context());val a=record("runtime.taplo-node","22.23.3");r.put(a);val t=r.tomlPackageGeneration();r.remove("unrelated","1");assertTrue(r.isTomlPackageGenerationCurrent(t));r.remove(a.familyId,a.version);assertFalse(r.isTomlPackageGenerationCurrent(t))}
    @Test fun pureTokensDoNotAcquireMutationAdmissionOrPreferenceLock(){val entered=CountDownLatch(1);val release=CountDownLatch(1);val context=context();val p=WorkspaceToolchainPreferences(context,File("/test/lock")){action->entered.countDown();check(release.await(3,TimeUnit.SECONDS));action()};val t=p.tomlSelectionGeneration();val worker=Thread{p.set("lsp.taplo-wasm","0.8.0")};worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));val start=System.nanoTime();assertTrue(p.isTomlSelectionGenerationCurrent(t));assertTrue(System.nanoTime()-start<100_000_000L);release.countDown();worker.join(3000);assertFalse(worker.isAlive);assertFalse(p.isTomlSelectionGenerationCurrent(t))}
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
        val pt = p.tomlSelectionGeneration();val rt = r.tomlPackageGeneration()
        reads.set(0)
        var created = false
        p.withTomlSelectionGeneration(pt) { TomlGenerationAdmission.withAdmission {
            check(p.isTomlSelectionGenerationCurrent(pt) && r.isTomlPackageGenerationCurrent(rt));created = true
        } }
        assertTrue(created);assertEquals(0,reads.get())
        p.set("runtime.taplo-node", "unsupported")
        assertThrows(IllegalStateException::class.java) { p.withTomlSelectionGeneration(pt) { error("factory reached") } }
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
        val old=record("lsp.taplo-wasm","0.8.0");first.put(old)
        val before=second.tomlPackageGeneration();hold.set(true)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer=Thread { try { first.put(old.copy(metadata=mapOf("generation" to "new"))) } catch(t:Throwable){failure.set(t)} }
        writer.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val during=second.tomlPackageGeneration();assertFalse(second.isTomlPackageGenerationCurrent(before))
        assertEquals(old,second.list().single())
        val attempted=CountDownLatch(1);val finished=CountDownLatch(1);val created=java.util.concurrent.atomic.AtomicBoolean(false)
        val admission=Thread { attempted.countDown();TomlGenerationAdmission.withAdmission { if(second.isTomlPackageGenerationCurrent(during))created.set(true) };finished.countDown() }
        admission.start();assertTrue(attempted.await(5,TimeUnit.SECONDS));assertFalse(finished.await(50,TimeUnit.MILLISECONDS))
        release.countDown();writer.join(5000);admission.join(5000)
        assertFalse(writer.isAlive);assertFalse(admission.isAlive);assertNull(failure.get())
        assertFalse(created.get());assertFalse(second.isTomlPackageGenerationCurrent(during))
        assertEquals("new",second.list().single().metadata["generation"])
    }
    @Test fun capturedBeforeProofSelectionCannotBindAfterChange() {
        val base=context();val p=WorkspaceToolchainPreferences(base,File("/test/proof"));p.set("lsp.taplo-wasm","0.8.0")
        val before=p.tomlSelectionGeneration();val observed=p.selections()
        val other=WorkspaceToolchainPreferences(base,File("/test/proof"));other.set("lsp.taplo-wasm","unsupported")
        assertEquals("0.8.0",observed["lsp.taplo-wasm"])
        assertFalse(p.isTomlSelectionGenerationCurrent(before))
        assertThrows(IllegalStateException::class.java) { p.withTomlSelectionGeneration(before) { error("old identity admitted") } }
    }

    @Test fun runtimeMutationAndVueIsolation() {
        val c=context();val r=ManagedPackageRegistry(c);val p=WorkspaceToolchainPreferences(c,File("/test/sdk"))
        val token=r.tomlPackageGeneration();val selection=p.tomlSelectionGeneration()
        r.put(record("lsp.vue"));p.set("lsp.vue","3.3.13")
        assertTrue(r.isTomlPackageGenerationCurrent(token));assertTrue(p.isTomlSelectionGenerationCurrent(selection))
        r.put(record("runtime.taplo-node","22.23.3"));p.set("runtime.taplo-node","22.23.3")
        assertFalse(r.isTomlPackageGenerationCurrent(token));assertFalse(p.isTomlSelectionGenerationCurrent(selection))
        val vue=r.vuePackageGeneration();r.remove("runtime.taplo-node","22.23.3");assertTrue(r.isVuePackageGenerationCurrent(vue))
    }
    @Test fun failedRegistryPublicationRevokesOldAndIntermediateTokens() {
        val base=context();var fail=false;var middle:Any?=null
        lateinit var registry:ManagedPackageRegistry
        val c=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val store=base.getSharedPreferences(name,mode)
                return object:SharedPreferences by store {override fun edit():SharedPreferences.Editor {
                    val e=store.edit();return object:SharedPreferences.Editor by e {
                        override fun putString(k:String,v:String?):SharedPreferences.Editor {e.putString(k,v);return this}
                        override fun commit():Boolean {if(fail){middle=registry.tomlPackageGeneration();return false};return e.commit()}
                    }
                }}
            }
        }
        registry=ManagedPackageRegistry(c);val old=record("lsp.taplo-wasm","0.8.0");registry.put(old)
        val before=registry.tomlPackageGeneration();fail=true
        assertThrows(IllegalStateException::class.java){registry.put(old.copy(metadata=mapOf("test" to "replacement")))}
        assertFalse(registry.isTomlPackageGenerationCurrent(before));assertFalse(registry.isTomlPackageGenerationCurrent(middle!!));assertEquals(old,registry.list().single())
    }

    @Test fun priorCSharpAndVueTokensDoNotChangeForTomlMutations() {
        val c=context();val r=ManagedPackageRegistry(c);val p=WorkspaceToolchainPreferences(c,File("/test/prior"))
        val cp=r.csharpPackageGeneration();val vp=r.vuePackageGeneration()
        val cs=p.csharpSelectionGeneration();val vs=p.vueSelectionGeneration()
        r.put(record("lsp.taplo-wasm","0.8.0"));p.set("runtime.taplo-node","22.23.3")
        assertTrue(r.isCSharpPackageGenerationCurrent(cp));assertTrue(r.isVuePackageGenerationCurrent(vp))
        assertTrue(p.isCSharpSelectionGenerationCurrent(cs));assertTrue(p.isVueSelectionGenerationCurrent(vs))
    }
    @Test fun terminalNodeAndPriorFamilyMutationsDoNotChangeTomlTokens() {
        val c=context();val r=ManagedPackageRegistry(c);val p=WorkspaceToolchainPreferences(c,File("/test/unrelated"))
        val rp=r.tomlPackageGeneration();val sp=p.tomlSelectionGeneration()
        for(f in listOf("runtime.node","lsp.csharp-ls","runtime.csharp-sdk","runtime.csharp-helper","lsp.vue","runtime.vue-helper")) {
            r.put(record(f));p.set(f,"1")
        }
        assertTrue(r.isTomlPackageGenerationCurrent(rp));assertTrue(p.isTomlSelectionGenerationCurrent(sp))
    }
    @Test fun changingAndRestoringSameSelectionDoesNotReviveOldToken() {
        val p=WorkspaceToolchainPreferences(context(),File("/test/aba"))
        p.set("lsp.taplo-wasm","0.8.0");val old=p.tomlSelectionGeneration()
        p.set("lsp.taplo-wasm","other");p.set("lsp.taplo-wasm","0.8.0")
        assertFalse(p.isTomlSelectionGenerationCurrent(old))
    }

}
