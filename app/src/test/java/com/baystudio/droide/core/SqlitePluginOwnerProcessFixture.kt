package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Invoked only by the separate-JVM evidence runner, with newly allocated test directories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqlitePluginOwnerProcessFixture {
    @Test fun isolatedProcessRole() = runBlocking {
        val role = System.getProperty("droide.owner.fixture.role") ?: return@runBlocking
        val root = File(checkNotNull(System.getProperty("droide.owner.fixture.root")))
        val store = SqlitePluginConversationHistory(root)
        try {
            when (role) {
                "holder" -> {
                    val id = store.beginTurn(null, "opencode", "Question", WorkspaceAgentMode.EDIT, "native-original").summary.id
                    store.appendPartial(id, listOf("first ", "\uD83D")); store.appendPartial(id, listOf("\uDE80"))
                    File(root, "fixture-id").writeText(id)
                    while (!File(root, "fixture-continue").exists()) delay(20)
                    store.appendPartial(id, listOf(" second"))
                    File(root, "fixture-continued").writeText("committed")
                    while (!File(root, "fixture-close").exists()) delay(20)
                    store.close(); File(root, "fixture-closed").writeText("closed")
                }
                "reader" -> {
                    val id = File(root, "fixture-id").readText()
                    val preview = store.load(id)!!
                    assertNotNull(preview.readOnlyReason); assertEquals(PluginHistoryStatus.RUNNING, preview.summary.status)
                    assertEquals("first 🚀", preview.messages.last().content)
                    assertTrue(runCatching { store.bind(id, "wrong-native") }.isFailure)
                    assertTrue(runCatching { store.delete(id) }.isFailure)
                    assertTrue(runCatching { store.appendPartial(id, listOf("foreign")) }.isFailure)
                    assertEquals(PluginHistoryStatus.RUNNING, store.listPage().entries.single().status)
                }
                "graceful" -> {
                    val id = File(root, "fixture-id").readText()
                    val restored = store.load(id)!!
                    assertNull(restored.readOnlyReason); assertEquals(PluginHistoryStatus.INTERRUPTED, restored.summary.status)
                    assertEquals("first 🚀 second", restored.messages.last().content)
                    assertEquals("native-original", restored.summary.nativeSessionId)
                    store.beginTurn(id, "opencode", "Continue", WorkspaceAgentMode.EDIT, restored.summary.nativeSessionId)
                    assertEquals("resumed", store.finish(id, "resumed", emptyList(), PluginHistoryStatus.COMPLETED).messages.last().content)
                }
                "crash" -> {
                    val id = File(root, "fixture-id").readText()
                    val preview = store.load(id)!!
                    assertNotNull(preview.readOnlyReason); assertEquals(PluginHistoryStatus.RUNNING, preview.summary.status)
                    assertEquals("first 🚀 second", preview.messages.last().content)
                    assertTrue(runCatching { store.beginTurn(id, "opencode", "Unsafe resume", WorkspaceAgentMode.EDIT, "native-original") }.isFailure)
                    val fresh = store.beginTurn(null, "opencode", "Independent", WorkspaceAgentMode.EDIT, "new-native")
                    assertNotEquals(id, fresh.summary.id); store.finish(fresh.summary.id, "new work", emptyList(), PluginHistoryStatus.COMPLETED)
                    assertEquals("first 🚀 second", store.load(id)!!.messages.last().content)
                    SQLiteDatabase.openDatabase(File(root, ".droide/sessions/plugins.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT native_id,status FROM conversations WHERE id=?", arrayOf(id)).use {
                            assertTrue(it.moveToFirst()); assertEquals("native-original", it.getString(0)); assertEquals("RUNNING", it.getString(1))
                        }
                        db.rawQuery("SELECT count(*) FROM live_parts WHERE conversation_id=?", arrayOf(id)).use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
                    }
                }
                else -> error("Unknown isolated fixture role")
            }
        } finally { store.close() }
        Unit
    }
}
