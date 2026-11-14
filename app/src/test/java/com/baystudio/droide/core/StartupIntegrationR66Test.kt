package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Authored actual Android boundaries; requires unresolved project test dependencies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class StartupIntegrationR66Test {
 @Before fun openEpoch(){val c=RuntimeEnvironment.getApplication();ApplicationWorkAdmission.initialize(c);DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow();DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())}
 private fun request(action:String)=Intent().setAction(action).putExtra(DevelopmentForegroundService.EXTRA_EPOCH,ApplicationWorkAdmission.currentEpoch)
 @Test fun freshResidentDeliveredAfterClosureStopsPromptly(){val queued=request("com.baystudio.droide.DEV_FGS_RESIDENT");ApplicationWorkAdmission.closeForExit();val controller=Robolectric.buildService(DevelopmentForegroundService::class.java).create();try{controller.get().onStartCommand(queued,0,1);assertTrue(shadowOf(controller.get()).isStoppedBySelf)}finally{controller.destroy()}}
 @Test fun promotedOwnerSurvivesLateStartDuringPreservation(){val controller=Robolectric.buildService(DevelopmentForegroundService::class.java).create();val service=controller.get();var canceled=0;val token="r66-active-owner-0123456789";DevelopmentForegroundSessionRegistry.register(token){canceled++};try{service.onStartCommand(request("com.baystudio.droide.DEV_FGS_START").putExtra("token",token).putExtra("label","Fixture"),0,1);ApplicationWorkAdmission.closeForExit();service.onStartCommand(request("com.baystudio.droide.DEV_FGS_RESIDENT"),0,2);assertFalse(shadowOf(service).isStoppedBySelf);assertEquals(0,canceled);assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(token))}finally{controller.destroy()}}
 @Test fun wrongMetadataTypeIsRejectedWithoutChangingPreferences(){val c=RuntimeEnvironment.getApplication();val p=c.getSharedPreferences("droide_github_account_state_v1",Context.MODE_PRIVATE);p.edit().putString("login","fixture").putString("user_id","invalid").commit();val before=p.all.toMap();assertThrows(ClassCastException::class.java){GitHubAccountStateStore(c).validateStoredTypes()};assertEquals(before,p.all)}
 @Test fun wrongEncryptedSlotTypeDoesNotDecryptOrDelete(){val c=RuntimeEnvironment.getApplication();val p=c.getSharedPreferences("droide_github_account_secrets_v1",Context.MODE_PRIVATE);p.edit().putBoolean("pending_oauth",true).commit();val before=p.all.toMap();assertThrows(ClassCastException::class.java){GitHubAccountSecretStore(c).validateStoredTypes()};assertEquals(before,p.all)}
 @Test fun corruptPluginIndexRemainsRejectedAndUnmodified(){val c=RuntimeEnvironment.getApplication();val p=File(c.filesDir,"agent-plugins/index.json");p.parentFile.mkdirs();p.writeText("invalid fixture");val before=p.readBytes();assertThrows(IOException::class.java){AgentPluginStore(c).listVerified()};assertArrayEquals(before,p.readBytes())}
 @Test fun invalidSavedThemeCannotBecomeStartupPalette(){val root=Files.createTempDirectory("r66-theme-").toFile();try{val file=File(root,".droide/themes/fixture.json");file.parentFile.mkdirs();file.writeText("""{"name":"fixture","dark":"#123456","light":"#EEEEEE"}""");val manager=ThemeManager(root);manager.select("fixture");file.writeText("""{"name":"fixture","dark":"invalid","light":"#EEEEEE"}""");val before=file.readBytes();assertEquals("droide-dark",ThemeManager(root).snapshot().name);assertEquals("fixture",manager.snapshot().name);assertArrayEquals(before,file.readBytes())}finally{PathSecurity.deleteTreeNoFollow(root)}}
}
