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
import org.robolectric.annotation.Config

/** Authored Android/Robolectric intent-boundary cases; device handlers/network are never launched here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsOfficialLinkLauncherR74Test {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun everyWebDestinationUsesExactBrowsableViewIntent() {
        SettingsOfficialLink.entries.filterNot { it.isEmail }.forEach { link ->
            val intent = SettingsOfficialLinkLauncher.intentFor(link)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(link.url, intent.dataString)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        }
    }

    @Test fun supportUsesSendToForOnlyTheFixedMailRecipient() {
        val intent = SettingsOfficialLinkLauncher.intentFor(SettingsOfficialLink.SUPPORT)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:supportbaystudio@gmail.com", intent.dataString)
        assertTrue(intent.categories.isNullOrEmpty())
    }

    @Test fun noLaunchRequestContainsPrivateDataAttachmentsOrPermissionGrants() {
        SettingsOfficialLink.entries.forEach { link ->
            val intent = SettingsOfficialLinkLauncher.intentFor(link)
            assertNull(intent.extras)
            assertNull(intent.clipData)
            assertNull(intent.type)
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
    }

    @Test fun eachExplicitRequestDispatchesOnlyOnceAndReportsNoLoadedOrSentClaim() {
        val launched = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { launched += intent } }
        assertTrue(launched.isEmpty())
        SettingsOfficialLink.entries.forEach { assertNull(SettingsOfficialLinkLauncher.open(context, it)) }
        assertEquals(SettingsOfficialLink.entries.map { it.url }, launched.map { it.dataString })
    }

    @Test fun unavailableHandlerIsVisibleForWebAndEmailWithoutRawExceptionText() {
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException("private diagnostics") } }
        SettingsOfficialLink.entries.forEach { link ->
            val result = SettingsOfficialLinkLauncher.open(context, link)
            assertEquals(link.unavailableMessage, result)
            assertFalse(result!!.contains("private diagnostics"))
        }
    }

    @Test fun deniedExternalActivityReturnsTheSameSafeFallback() {
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { throw SecurityException("private diagnostics") } }
        SettingsOfficialLink.entries.forEach { assertEquals(it.unavailableMessage, SettingsOfficialLinkLauncher.open(context, it)) }
    }

    @Test fun creatingOrMutatingOneIntentCannotChangeTheNextFixedTarget() {
        val first = SettingsOfficialLinkLauncher.intentFor(SettingsOfficialLink.WEBSITE)
        first.data = android.net.Uri.parse("https://example.com/")
        first.putExtra(Intent.EXTRA_TEXT, "private")
        val second = SettingsOfficialLinkLauncher.intentFor(SettingsOfficialLink.WEBSITE)
        assertEquals(SettingsOfficialLink.WEBSITE.url, second.dataString)
        assertNull(second.extras)
    }

    @Test fun cancellationOrFailureDoesNotCreateAutomaticRetry() {
        var attempts = 0
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) {
            attempts++
            if (attempts == 1) throw ActivityNotFoundException()
        } }
        assertEquals(SettingsOfficialLink.WEBSITE.unavailableMessage, SettingsOfficialLinkLauncher.open(context, SettingsOfficialLink.WEBSITE))
        assertEquals(1, attempts)
        assertNull(SettingsOfficialLinkLauncher.open(context, SettingsOfficialLink.TERMS))
        assertEquals(2, attempts)
    }
}
