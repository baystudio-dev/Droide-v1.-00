package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Authentic schema5, never a schema6 database relabeled. Fixture evidence is retained. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FinishReceiptMigrationReviewTest {
    private data class Fixture(val root:File,val running:String,val retired:String) { val file get()=File(root,".droide/sessions/plugins.sqlite") }
    private fun fixture():Fixture {
        val f=Fixture(Files.createTempDirectory("f158-review-v5-").toFile(),PluginConversationHistory.newId(),PluginConversationHistory.newId())
        f.file.parentFile.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f.file,null).use { db ->
            db.execSQL("""CREATE TABLE conversations(id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,title TEXT NOT NULL,
                created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,native_id TEXT,mode TEXT NOT NULL,model_id TEXT,
                message_count INTEGER NOT NULL DEFAULT 0,part_count INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,owner_id TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX conversation_time ON conversations(updated_at DESC,id DESC)")
            db.execSQL("CREATE INDEX conversation_agent_time ON conversations(agent_id,updated_at DESC,id DESC)")
            db.execSQL("""CREATE TABLE message_parts(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL,logical_ordinal INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,
                activity TEXT,content_utf16 BLOB,PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("""CREATE TABLE live_parts(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL,content TEXT NOT NULL,activity_id TEXT,activity TEXT,content_utf16 BLOB,PRIMARY KEY(conversation_id,ordinal))""")
            db.execSQL("CREATE INDEX live_activity_id ON live_parts(conversation_id,activity_id)")
            db.execSQL("CREATE TABLE active_conversations(agent_id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE message_media(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,logical_ordinal INTEGER NOT NULL,refs TEXT NOT NULL,PRIMARY KEY(conversation_id,logical_ordinal))")
            for((id,status) in listOf(f.running to "RUNNING",f.retired to "INTERRUPTED")) {
                db.execSQL("INSERT INTO conversations VALUES(?,'opencode','legacy title',1,2,'legacy-native','EDIT','legacy-model',1,1,?,'foreign-schema5-owner')",arrayOf(id,status))
                db.execSQL("INSERT INTO message_parts VALUES(?,0,0,'user','legacy prompt',NULL,NULL)",arrayOf(id))
                db.execSQL("INSERT INTO live_parts VALUES(?,0,'',NULL,NULL,?)",arrayOf(id,byteArrayOf(0x41,0x00,0x00,0xD8.toByte())))
            }
            db.execSQL("INSERT INTO active_conversations VALUES('opencode',?)",arrayOf(f.running))
            db.version=5
        }
        return f
    }
    private fun rows(f:Fixture):List<String> = SQLiteDatabase.openDatabase(f.file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
        buildList {
            add("version:${db.version}")
            db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name",null).use{c->while(c.moveToNext())add(c.getString(0))}
            db.rawQuery("SELECT id,owner_id,status,message_count,part_count,native_id,model_id FROM conversations ORDER BY id",null).use{c->while(c.moveToNext())add((0..6).joinToString("|"){c.getString(it)})}
            db.rawQuery("SELECT conversation_id,ordinal,content,hex(content_utf16) FROM live_parts ORDER BY conversation_id,ordinal",null).use{c->while(c.moveToNext())add((0..3).joinToString("|"){c.getString(it)})}
        }
    }
    @Test fun migrationFailureRollsBackEntireSchemaAndPreservesForeignLiveEvidence()=runBlocking {
        val f=fixture();val before=rows(f);val store=SqlitePluginConversationHistory(f.root);var hit=false
        store.lifecycleCheckpoint={if(it=="before-finish-schema-commit"){hit=true;throw IOException("migration review fault")}}
        try { assertTrue(runCatching{store.listPage()}.isFailure);assertTrue(hit);assertEquals(before,rows(f)) }
        finally {store.lifecycleCheckpoint={};store.close()}
        val good=SqlitePluginConversationHistory(f.root)
        try {assertEquals(2,good.listPage().entries.size)}finally{good.close()}
        SQLiteDatabase.openDatabase(f.file.path,null,SQLiteDatabase.OPEN_READONLY).use{assertEquals(6,it.version)}
    }
    @Test fun migrationNeverInventsAuthorityAndRetiredEvidenceCanContinueNormally()=runBlocking {
        val f=fixture();val store=SqlitePluginConversationHistory(f.root)
        try {
            assertEquals(2,store.listPage().entries.size)
            SQLiteDatabase.openDatabase(f.file.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
                assertEquals(6,db.version)
                db.rawQuery("SELECT owner_id,turn_id,finish_digest FROM conversations ORDER BY id",null).use{c->
                    while(c.moveToNext()){assertEquals("foreign-schema5-owner",c.getString(0));assertTrue(c.isNull(1));assertTrue(c.isNull(2))}
                }
                db.rawQuery("SELECT hex(content_utf16) FROM live_parts",null).use{c->while(c.moveToNext())assertEquals("410000D8",c.getString(0))}
            }
            val running=store.load(f.running)!!
            assertNotNull(running.readOnlyReason);assertEquals(PluginHistoryStatus.RUNNING,running.summary.status)
            assertEquals(listOf("legacy prompt","A\uD800"),running.messages.map{it.content})
            assertTrue(runCatching{store.beginCheckpointTurn(f.running,"opencode","new",WorkspaceAgentMode.EDIT,null)}.isFailure)
            val retired=store.load(f.retired)!!
            assertNull(retired.readOnlyReason);assertEquals(listOf("legacy prompt","A\uD800"),retired.messages.map{it.content})
            val admitted=store.beginCheckpointTurn(f.retired,"opencode","new",WorkspaceAgentMode.EDIT,"legacy-native")
            val request=PluginHistoryFinishRequest(admitted.identity,"new answer",emptyList(),PluginHistoryStatus.COMPLETED)
            val saved=store.finishCheckpoint(request)
            assertEquals(listOf("legacy prompt","A\uD800","new","new answer"),saved.messages.map{it.content})
            assertEquals(saved,store.finishCheckpoint(request))
        }finally{store.close()}
        val reopened=SqlitePluginConversationHistory(f.root)
        try{assertNotNull(reopened.load(f.running)!!.readOnlyReason);assertEquals(PluginHistoryStatus.RUNNING,reopened.listPage().entries.first{it.id==f.running}.status)}finally{reopened.close()}
    }
}
