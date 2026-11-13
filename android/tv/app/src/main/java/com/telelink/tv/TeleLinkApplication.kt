package com.telelink.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.UUID

data class AuthorizedRemoteLaunch(
    val type: String,
    val value: String,
    val integrated: Boolean
)

class RemoteLaunchAuthorizations {
    private var current: Pair<String, AuthorizedRemoteLaunch>? = null

    @Synchronized
    fun authorize(request: AuthorizedRemoteLaunch): String {
        val token = UUID.randomUUID().toString()
        current = token to request
        return token
    }

    @Synchronized
    fun consume(token: String): AuthorizedRemoteLaunch? {
        val stored = current ?: return null
        if (stored.first != token) return null
        current = null
        return stored.second
    }

    @Synchronized
    fun revoke(token: String) {
        if (current?.first == token) current = null
    }
}

/** Tracks whether Android currently has a visible TeleLink activity for foreground launches. */
class TeleLinkApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val remoteLaunchAuthorizations = RemoteLaunchAuthorizations()

    fun authorizeRemoteLaunch(request: AuthorizedRemoteLaunch): String =
        remoteLaunchAuthorizations.authorize(request)

    fun consumeRemoteLaunch(token: String): AuthorizedRemoteLaunch? =
        remoteLaunchAuthorizations.consume(token)

    fun revokeRemoteLaunch(token: String) = remoteLaunchAuthorizations.revoke(token)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        visibleActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        visibleActivityCount = (visibleActivityCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile
        private var visibleActivityCount = 0

        val hasVisibleActivity: Boolean
            get() = visibleActivityCount > 0
    }
}
