package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GracefulRetirementIndependentTest {
 private val home=Files.createTempDirectory("f147-graceful-review-").toFile()
 private val stores=mutableListOf<SqlitePluginConversationHistory>()
 private fun store()=SqlitePluginConversationHistory(home).also { stores+=it }
 private fun db()=SQLiteDatabase.openDatabase(File(home,".droide/sessions/plugins.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE)
 private suspend fun begin(s:PluginConversationHistory,id:String?=null)=s.beginTurn(id,"opencode","Question",WorkspaceAgentMode.EDIT,"native").summary.id
 private fun rows(id:String)=db().use { d -> d.rawQuery("SELECT ordinal,content,hex(content_utf16),activity_id,activity FROM live_parts WHERE conversation_id=? ORDER BY ordinal",arrayOf(id)).use { c -> buildList { while(c.moveToNext()) add((0..4).map { if(c.isNull(it)) null else c.getString(it) }) } } }
 @After fun cleanup()=runBlocking { stores.asReversed().forEach { it.lifecycleCheckpoint={}; it.close() }; PathSecurity.deleteTreeNoFollow(home); Unit }
 @Test fun retirementOnlyChangesOwnRunningStatusAndNeverPayload()=runBlocking {
  val a=store();val b=store();val aid=begin(a);val bid=begin(b)
  a.appendPartial(aid,listOf("own retained"));b.appendPartial(bid,listOf("foreign retained"))
  val own=rows(aid);val foreign=rows(bid);a.close()
  assertEquals(own,rows(aid));assertEquals(foreign,rows(bid))
  val states=b.listPage().entries.associate { it.id to it.status }
  assertEquals(PluginHistoryStatus.INTERRUPTED,states[aid]);assertEquals(PluginHistoryStatus.RUNNING,states[bid])
  b.appendPartial(bid,listOf(" continuation"));b.finish(bid,null,emptyList(),PluginHistoryStatus.COMPLETED);Unit
 }
 @Test fun malformedRetiredPayloadSurvivesFailedLoadAndBeginWithoutLeakingClosedWriter()=runBlocking {
  val a=store();val id=begin(a);a.appendPartial(id,listOf("intact"));a.appendPartial(id,listOf("bad"))
  db().use { it.execSQL("UPDATE live_parts SET content_utf16=X'01' WHERE conversation_id=? AND ordinal=1",arrayOf(id)) }
  val before=rows(id);a.close();assertEquals(before,rows(id))
  assertTrue(ExportWriterAdmission.snapshotBlockers(home).isEmpty())
  val b=store();assertTrue(runCatching { b.load(id) }.isFailure);assertEquals(before,rows(id))
  assertTrue(runCatching { begin(b,id) }.isFailure);assertEquals(before,rows(id))
  db().use { d -> d.rawQuery("SELECT status,message_count,part_count FROM conversations WHERE id=?",arrayOf(id)).use { c -> c.moveToFirst();assertEquals("INTERRUPTED",c.getString(0));assertEquals(1,c.getInt(1));assertEquals(1,c.getInt(2)) } }
  Unit
 }
 @Test fun directBeginPromotesRetiredPrefixBeforeNextPromptExactlyOnce()=runBlocking {
  val a=store();val id=begin(a);a.appendPartial(id,listOf("prefix \uD83D"));a.appendPartial(id,listOf("\uDE80"));a.close()
  val b=store();val admitted=b.beginTurn(id,"opencode","Next question",WorkspaceAgentMode.EDIT,"native")
  assertEquals(listOf("Question","prefix 🚀","Next question"),admitted.messages.map { it.content })
  assertTrue(rows(id).isEmpty());b.finish(id,"reply",emptyList(),PluginHistoryStatus.COMPLETED)
  assertEquals(listOf("Question","prefix 🚀","Next question","reply"),b.load(id)!!.messages.map { it.content });Unit
 }
 private fun legacy(status:String):String {
  val id=PluginConversationHistory.newId();val file=File(home,".droide/sessions/plugins.sqlite").apply { parentFile.mkdirs() }
  SQLiteDatabase.openOrCreateDatabase(file,null).use { d ->
   d.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,agent_id TEXT NOT NULL,title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,native_id TEXT,mode TEXT NOT NULL,model_id TEXT,message_count INTEGER NOT NULL DEFAULT 0,part_count INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,owner_id TEXT NOT NULL)")
   d.execSQL("CREATE TABLE message_parts(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,logical_ordinal INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,activity TEXT,content_utf16 BLOB,PRIMARY KEY(conversation_id,ordinal))")
   d.execSQL("CREATE TABLE live_parts(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,content TEXT NOT NULL,activity_id TEXT,activity TEXT,content_utf16 BLOB,PRIMARY KEY(conversation_id,ordinal))")
   d.execSQL("CREATE TABLE active_conversations(agent_id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE)")
   d.execSQL("CREATE TABLE message_media(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,logical_ordinal INTEGER NOT NULL,refs TEXT NOT NULL,PRIMARY KEY(conversation_id,logical_ordinal))")
   d.execSQL("INSERT INTO conversations VALUES(?,'opencode','Legacy',1,1,'legacy-native','EDIT',NULL,1,1,?,'legacy-owner')",arrayOf(id,status))
   d.execSQL("INSERT INTO message_parts VALUES(?,0,0,'user','Legacy prompt',NULL,NULL)",arrayOf(id))
   d.execSQL("INSERT INTO live_parts VALUES(?,0,'Legacy durable prefix',NULL,NULL,NULL)",arrayOf(id))
   d.execSQL("INSERT INTO active_conversations VALUES('opencode',?)",arrayOf(id));d.version=4
  };return id
 }
 @Test fun actualVersionFourUnknownRunningHistoryIsPreviewedWithoutAnyLeaseEnrollment()=runBlocking {
  val id=legacy("RUNNING");val before=rows(id);val b=store();val w=b.latest("opencode")!!
  assertNotNull(w.readOnlyReason);assertEquals("legacy-native",w.summary.nativeSessionId)
  assertEquals(listOf("Legacy prompt","Legacy durable prefix"),w.messages.map { it.content })
  assertEquals(before,rows(id));b.close();assertEquals(before,rows(id))
  db().use { d -> assertEquals(6,d.version);d.rawQuery("SELECT status,owner_id FROM conversations WHERE id=?",arrayOf(id)).use { c -> c.moveToFirst();assertEquals("RUNNING",c.getString(0));assertEquals("legacy-owner",c.getString(1)) } }
  assertFalse(File(home,".droide/sessions").list()!!.any { it.startsWith(".plugin-owner-") });Unit
 }
 @Test fun actualVersionFourNonRunningRetainedPartsPromoteBeforeContinuation()=runBlocking {
  val id=legacy("STOPPED");val b=store();b.bind(id,"resumable-native","model")
  val w=b.beginTurn(id,"opencode","Next legacy question",WorkspaceAgentMode.EDIT,"resumable-native")
  assertEquals(listOf("Legacy prompt","Legacy durable prefix","Next legacy question"),w.messages.map { it.content })
  assertEquals("resumable-native",w.summary.nativeSessionId);assertTrue(rows(id).isEmpty())
  b.finish(id,"legacy resumed",emptyList(),PluginHistoryStatus.COMPLETED);Unit
 }
 @Test fun closingUnusedStoreDoesNotCreateHistory()=runBlocking { store().close();assertFalse(File(home,".droide").exists()) }
}
