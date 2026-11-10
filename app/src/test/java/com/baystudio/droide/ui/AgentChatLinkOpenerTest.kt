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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentChatLinkOpenerTest {
    @Test fun browserIntentPreservesTheWholeAuthUrlWithoutNormalization() {
        val context = RuntimeEnvironment.getApplication()
        val url = "HTTPS://accounts.example.test/auth?state=A%2BB%3D&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2F"
        assertNull(openAgentChatLink(context, url))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun badLinksNeverLaunchAndBrowserFailuresDoNotExposeSecrets() {
        val context = RuntimeEnvironment.getApplication()
        for (url in listOf("javascript:alert(1)", "https:///missing", "file:///data/file")) assertNotNull(openAgentChatLink(context, url))
        assertNull(shadowOf(context).nextStartedActivity)
        for (failure in listOf(ActivityNotFoundException("token=secret"), SecurityException("token=secret"))) {
            val rejected = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            val error = openAgentChatLink(rejected, "https://example.test/auth?token=secret")!!
            assertTrue(error.contains("Copy the response"))
            assertFalse(error.contains("secret"))
        }
    }

    @Test fun clipboardFailureReturnsARecoverableMessage() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? { throw SecurityException("clipboard private") }
        }
        val error = copyAgentText(context, "private response", "Droide response")!!
        assertTrue(error.contains("smaller section"))
        assertFalse(error.contains("private"))
    }
}
