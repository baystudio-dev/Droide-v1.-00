package com.baystudio.droide.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TerminalLinkOpenerTest {
    @Test fun explicitOpenUsesBrowsableIntentWithoutDecodingAuthParameters() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://accounts.example.test/auth?redirect_uri=http%3A%2F%2Flocalhost%3A3000%2F&state=A%2BB%3D"
        assertTrue(TerminalLinkOpener.open(context, url))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun nonWebSchemesAndMalformedLinksNeverLaunchAnActivity() {
        val context = RuntimeEnvironment.getApplication()
        for (url in listOf("intent://app#Intent;end", "javascript:alert(1)", "file:///tmp/script.sh", "https:///missing-host")) {
            assertFalse(TerminalLinkOpener.open(context, url))
        }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun explicitHyperlinkDoesNotTrimValidTrailingPathPunctuation() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://example.test/exact-path."
        assertTrue(TerminalLinkOpener.open(context, url))
        assertEquals(url, shadowOf(context).nextStartedActivity.dataString)
    }

    @Test fun missingOrRestrictedBrowserIsRecoverableWithoutExposingAuthUrl() {
        for (failure in listOf(ActivityNotFoundException("missing"), SecurityException("denied"))) {
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            assertFalse(TerminalLinkOpener.open(context, "https://example.test/?state=private-value"))
            assertTrue(ShadowToast.getTextOfLatestToast().contains("copy it from the terminal"))
            assertFalse(ShadowToast.getTextOfLatestToast().contains("private-value"))
        }
    }
}
