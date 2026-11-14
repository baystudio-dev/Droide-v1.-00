package com.baystudio.droide.core

import android.content.Context
import com.baystudio.droide.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EnvironmentSetupRequired : IllegalStateException(
    "Install the Ubuntu environment from the setup screen first. Your files remain available in the editor.",
)

internal object UbuntuEnvironmentSetup {
    private const val PREFERENCES = "droide_environment_setup_v1"
    private const val CONSENT = "canonical_archive_sha256"
    private const val DEFERRED = "setup_deferred"
    private val requests = MutableStateFlow(0L)

    fun requireUserSetup() {
        synchronized(requests) { requests.value++ }
        throw EnvironmentSetupRequired()
    }

    fun requireConsent(context: Context) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(CONSENT, null) ==
            FoundryUbuntuBaseManifest.ROOTFS_SHA256) { "Ubuntu download has not been approved" }
    }

    internal fun existingEnvironment(filesDir: File): Boolean {
        val base = filesDir.resolve("guest")
        if (!retainedDirectory(base)) return false
        val current = base.resolve(FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR)
        val previous = base.resolve(".previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")
        // Even an unhealthy retained tree is user data. Only the environment manager may repair it.
        return retainedDirectory(current) || (retainedDirectory(previous.parentFile!!) && retainedDirectory(previous))
    }

    private fun retainedDirectory(path: File): Boolean {
        val attributes = try { Files.readAttributes(path.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return false }
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "Existing Ubuntu data needs review; it was not replaced." }
        return true
    }

    fun create(context: Context, scope: CoroutineScope): EnvironmentSetupController {
        val app = context.applicationContext
        val epoch = ApplicationWorkAdmission.currentEpoch
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val observedRequest = requests.value
        val controller = EnvironmentSetupController(
            scope, enabled = BuildConfig.FDROID_DISTRIBUTION,
            inspect = { withContext(Dispatchers.IO) {
                EnvironmentSetupInspection(existingEnvironment(app.filesDir), preferences.getBoolean(DEFERRED, false))
            } },
            install = { progress ->
                val job = currentCoroutineContext()[Job]!!
                val foreground = DevelopmentForegroundService.startRequired(app, "Installing Ubuntu environment", job::cancel)
                try {
                    withContext(Dispatchers.IO) {
                        check(preferences.edit().putString(CONSENT, FoundryUbuntuBaseManifest.ROOTFS_SHA256)
                            .putBoolean(DEFERRED, false).commit()) { "Unable to save installation consent" }
                    }
                    FoundryUbuntuGuestEnvironmentManager(app).installFromUser(progress)
                } finally { DevelopmentForegroundService.finish(app, foreground) }
            },
            defer = { withContext(Dispatchers.IO) {
                check(preferences.edit().putBoolean(DEFERRED, true).commit()) { "Unable to save setup preference" }
            } },
            admit = { ApplicationWorkAdmission.isCurrentOpen(epoch) },
        )
        if (BuildConfig.FDROID_DISTRIBUTION) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.collect { if (it != observedRequest) controller.show() }
        }
        return controller
    }
}
