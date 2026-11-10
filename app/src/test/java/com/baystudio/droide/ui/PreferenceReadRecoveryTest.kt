package com.baystudio.droide.ui
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class PreferenceReadRecoveryTest {
 @get:Rule val compose=createComposeRule()
 @Test fun failureVisibleAndExplicitRetryRestoresReadyState(){var reads=0;compose.setContent{DroideTheme{val r=rememberPreferenceReadRecovery("fixture","last"){flow{reads++;if(reads==1)throw IOException("private");emit("restored")}};Column{Text("${r.state.value}:${r.state.ready}");PreferenceReadFailureRow("Preferences",r.state.failed,r::retry)}}};compose.onNodeWithText("Preferences could not be read. Stored data was kept.").assertIsDisplayed();compose.onNodeWithText("last:false").assertExists();compose.onNodeWithText("Retry").performClick();compose.onNodeWithText("restored:true").assertExists();compose.runOnIdle{assertEquals(2,reads)}}
 @Test fun ownerChangeCancelsOldCollectorWithoutFailureBanner(){var key by mutableStateOf("first");var canceled=0;compose.setContent{DroideTheme{val r=rememberPreferenceReadRecovery(key,"initial"){flow<String>{try{awaitCancellation()}finally{canceled++}}};PreferenceReadFailureRow("Preferences",r.state.failed,r::retry)}};compose.runOnIdle{key="second"};compose.waitForIdle();compose.runOnIdle{assertEquals(1,canceled)};compose.onNodeWithText("Retry").assertDoesNotExist()}
 @Test fun healthyObserverAddsNoFailureRow(){compose.setContent{DroideTheme{val r=rememberPreferenceReadRecovery("healthy","old"){flow{emit("ready")}};PreferenceReadFailureRow("Preferences",r.state.failed,r::retry)}};compose.onNodeWithText("Retry").assertDoesNotExist()}
}
