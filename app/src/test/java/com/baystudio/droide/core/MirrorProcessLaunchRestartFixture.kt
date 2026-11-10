package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

object MirrorProcessLaunchRestartFixture {
    @JvmStatic fun main(args: Array<String>) = JUnitCore.main(Worker::class.java.name)
    @RunWith(RobolectricTestRunner::class)
    @Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    class Worker {
        @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
        @Test fun scenario() = runBlocking {
            val root = File(checkNotNull(System.getProperty("droide.launch.root")))
            val mode = checkNotNull(System.getProperty("droide.launch.mode"))
            val boundary = System.getProperty("droide.launch.boundary", "") ?: ""
            val source = root.resolve("source"); val working = root.resolve("working")
            val directory = root.resolve(".working-sync-authority")
            fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working,
                processCheckpoint = checkpoint, processBootIdentity = { null })
            fun db() = SQLiteDatabase.openDatabase(directory.resolve("state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE)
            fun binding(db: SQLiteDatabase) = db.rawQuery("SELECT binding FROM meta", null).use { check(it.moveToFirst()); it.getString(0) }
            suspend fun prepare() {
                check(source.mkdir()); source.resolve("file").writeText("preserved")
                LocalAgentWorkspaceMirror.prepareRetained(source, working)
            }
            fun ready() { root.resolve("ready").writeText(mode) }
            fun waitRelease() {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                while (!root.resolve("release").isFile && System.nanoTime() < deadline) Thread.sleep(10)
                check(root.resolve("release").isFile) { "Coordinator did not release live actor" }
            }
            fun legacy5() = db().use { database ->
                database.beginTransaction()
                try {
                    val owners = MirrorProcessCustodyLedger.read(database, binding(database))
                    database.execSQL("ALTER TABLE process_owners RENAME TO launch_owners")
                    database.execSQL("CREATE TABLE process_owners(id TEXT PRIMARY KEY NOT NULL,runtime_id TEXT NOT NULL,device_identity TEXT,boot_id TEXT)")
                    database.execSQL("UPDATE process_custody SET expected_count=0,members_digest=''")
                    database.version = 5
                    var prior = emptyList<LegacyV5ProcessCustodyLedger.Owner>()
                    for (owner in owners) {
                        val old = LegacyV5ProcessCustodyLedger.Owner(owner.id, owner.runtimeId, owner.boot)
                        LegacyV5ProcessCustodyLedger.append(database, binding(database), prior, old); prior = prior + old
                    }
                    if (owners.isEmpty()) {
                        val temporary = LegacyV5ProcessCustodyLedger.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                        LegacyV5ProcessCustodyLedger.append(database, binding(database), emptyList(), temporary)
                        LegacyV5ProcessCustodyLedger.confirm(database, binding(database), listOf(temporary), temporary)
                    }
                    database.execSQL("DROP TABLE launch_owners"); database.setTransactionSuccessful()
                } finally { database.endTransaction() }
            }
            when (mode) {
                "kill" -> {
                    prepare()
                    val cancel = boundary.startsWith("writer-reservation-") || boundary == "writer-boot-reconciliation-before-commit"
                    if (cancel) authority().processCustody(UUID.randomUUID().toString()).acquire(working)
                    val observed = authority { if (it == boundary) { ready(); CountDownLatch(1).await() } }
                    if (cancel) observed.requireNoPendingProcessOwners()
                    else observed.processCustody().acquire(working).withFactory({}, { root.resolve("unexpected-factory").writeText("bad") })
                    fail("Expected process-kill boundary was not reached")
                }
                "verify-kill" -> {
                    if (boundary.endsWith("before-commit")) {
                        val database = directory.resolve("state.sqlite"); val journal = directory.resolve("state.sqlite-journal")
                        assertTrue(journal.isFile); val bytes = database.readBytes(); val sidecar = journal.readBytes()
                        val failure = runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull()
                        assertTrue(failure.toString(), failure?.message.orEmpty().contains("sidecars"))
                        assertArrayEquals(bytes, database.readBytes()); assertArrayEquals(sidecar, journal.readBytes())
                    } else if (boundary == "writer-factory-entry-after-commit") {
                        assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
                        assertEquals(1, authority().pendingProcessOwners().size)
                        db().use { assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.FACTORY_ENTERED), MirrorProcessCustodyLedger.launchStates(it).values.toSet()) }
                    } else {
                        db().use { assertEquals(setOf(MirrorProcessCustodyLedger.LaunchState.CANCELLED_NO_START), MirrorProcessCustodyLedger.launchStates(it).values.toSet()) }
                        authority().requireNoPendingProcessOwners(); assertTrue(authority().pendingProcessOwners().isEmpty())
                    }
                    assertFalse(root.resolve("unexpected-factory").exists())
                }
                "live-prepared", "live-entered" -> {
                    prepare(); val receipt = authority().processCustody().acquire(working)
                    var child: Process? = null
                    try {
                        if (mode == "live-entered") receipt.withFactory({}, {
                            child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start()
                        })
                        ready(); waitRelease()
                        if (mode == "live-prepared") {
                            val error = runCatching { receipt.withFactory({}, {
                                child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start()
                                root.resolve("unexpected-factory").writeText("bad")
                            }) }.exceptionOrNull()
                            assertNotNull(error); assertNull(child)
                        } else assertTrue(checkNotNull(child).isAlive)
                    } finally {
                        child?.let { it.destroyForcibly(); assertTrue(it.waitFor(5, TimeUnit.SECONDS)) }
                        receipt.close()
                    }
                }
                "revoke-live-prepared" -> { authority().requireNoPendingProcessOwners(); assertTrue(authority().pendingProcessOwners().isEmpty()) }
                "refuse-live-entered" -> {
                    assertNotNull(runCatching { authority().requireNoPendingProcessOwners() }.exceptionOrNull())
                    assertEquals(1, authority().pendingProcessOwners().size)
                }
                "legacy-owner", "legacy-validated" -> {
                    prepare(); var child: Process? = null
                    if (mode == "legacy-owner") authority().processCustody().acquire(working).withFactory({}, {
                        child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(working).start()
                    }) else authority().processCustody().acquire(working).close()
                    legacy5()
                    try {
                        db().use { database ->
                            LegacyV5ProcessCustodyLedger.validateSchema(database, true)
                            val old = LegacyV5ProcessCustodyLedger.read(database, binding(database))
                            ready(); waitRelease()
                            child?.let { assertTrue(it.isAlive); it.destroyForcibly(); assertTrue(it.waitFor(5, TimeUnit.SECONDS)) }
                            database.beginTransaction()
                            try {
                                if (mode == "legacy-owner") {
                                    assertEquals(5, database.version)
                                    LegacyV5ProcessCustodyLedger.confirm(database, binding(database), old, old.single())
                                    database.setTransactionSuccessful()
                                } else {
                                    assertEquals(6, database.version)
                                    assertNotNull(runCatching { LegacyV5ProcessCustodyLedger.read(database, binding(database)) }.exceptionOrNull())
                                    assertNotNull(runCatching { LegacyV5ProcessCustodyLedger.append(database, binding(database), emptyList(),
                                        LegacyV5ProcessCustodyLedger.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())) }.exceptionOrNull())
                                }
                            } finally { database.endTransaction() }
                        }
                    } finally { child?.let { if (it.isAlive) { it.destroyForcibly(); check(it.waitFor(5, TimeUnit.SECONDS)) } } }
                }
                "refuse-upgrade" -> {
                    assertNotNull(runCatching { authority().processCustody().acquire(working) }.exceptionOrNull())
                    db().use { assertEquals(5, it.version) }
                }
                "upgrade" -> { authority().processCustody().acquire(working).close(); db().use { assertEquals(6, it.version) } }
                else -> error("Unexpected fixture mode $mode")
            }
            assertEquals("preserved", source.resolve("file").readText()); assertEquals("preserved", working.resolve("file").readText())
            println("LAUNCH_PERMISSION_VERIFIED $mode $boundary")
        }
    }
}
