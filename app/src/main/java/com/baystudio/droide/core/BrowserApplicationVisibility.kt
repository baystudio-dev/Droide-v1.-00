package com.baystudio.droide.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

internal class BrowserApplicationVisibility(private val onVisibilityChanged: (Boolean) -> Unit) :
    Application.ActivityLifecycleCallbacks {
    private val visibleActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    var isVisible = false
        private set

    private fun publish() {
        val visible = visibleActivities.isNotEmpty()
        if (visible == isVisible) return
        isVisible = visible
        onVisibilityChanged(visible)
    }

    override fun onActivityStarted(activity: Activity) { visibleActivities.add(activity); publish() }
    override fun onActivityStopped(activity: Activity) { visibleActivities.remove(activity); publish() }
    override fun onActivityDestroyed(activity: Activity) { visibleActivities.remove(activity); publish() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
