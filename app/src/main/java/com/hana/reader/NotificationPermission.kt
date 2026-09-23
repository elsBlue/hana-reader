package com.hana.reader

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Defer POST_NOTIFICATIONS until the first Listen / ReadingService.start.
 * Ask at most once per install (unless already granted).
 */
object NotificationPermission {
    private const val PREF = "hana"
    private const val ASKED_KEY = "notif_asked_v1"
    private var currentActivity: WeakReference<Activity>? = null
    @Volatile private var bound = false

    fun bind(application: Application) {
        if (bound) return
        bound = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Call from first Listen / ReadingService.start when permission is needed. */
    fun requestOnceIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val activity = currentActivity?.get()
            ?: (context as? Activity)
            ?: return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = activity.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_KEY, false)) return
        prefs.edit().putBoolean(ASKED_KEY, true).apply()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            12
        )
    }
}
