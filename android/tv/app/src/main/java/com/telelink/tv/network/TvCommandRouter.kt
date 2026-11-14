package com.telelink.tv.network

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.net.toUri
import android.provider.Settings
import com.telelink.tv.accessibility.TvRemoteAccessibilityService
import com.telelink.tv.browser.TvBrowserActivity
import com.telelink.tv.browser.TvBrowserBridge
import org.json.JSONObject

data class CommandResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null
)

class TvCommandRouter(
    private val context: Context,
    private val onBackgroundLaunchRequested: (Intent) -> Unit = {}
) {
    fun execute(command: JSONObject, onComplete: (CommandResult) -> Unit) {
        val type = command.optString("type")
        val mediaAction = when (type) {
            "MEDIA_PLAY" -> "PLAY"
            "MEDIA_PAUSE" -> "PAUSE"
            "MEDIA_SEEK" -> "SEEK"
            "MEDIA_CONTROL" -> command.optString("action")
            else -> null
        }
        if (mediaAction != null) {
            val position = if (type == "MEDIA_SEEK" || command.has("positionMs")) {
                command.optLong("positionMs")
            } else null
            val submitted = TvBrowserBridge.control(mediaAction, position) { accepted ->
                onComplete(
                    if (accepted) CommandResult(true, "$mediaAction applied to TV video")
                    else CommandResult(false, "No compatible video accepted $mediaAction")
                )
            }
            if (!submitted) onComplete(CommandResult(false, "No controllable TeleLink video is open"))
            return
        }

        if ((type == "TEXT_INPUT" || type == "SHARE_TEXT") &&
            TvRemoteAccessibilityService.instance() == null
        ) {
            // No Accessibility: type into the TeleLink browser's focused text box instead.
            val text = command.optString("text")
            if (text.length > MAX_TEXT_LENGTH) {
                onComplete(CommandResult(false, "Text is too long"))
                return
            }
            val submitted = TvBrowserBridge.insertText(text) { accepted ->
                onComplete(
                    if (accepted) CommandResult(true, "Text entered in the TV browser")
                    else CommandResult(false, "Select a text box in the TeleLink browser first")
                )
            }
            if (!submitted) onComplete(CommandResult(false, accessibilityRequiredMessage()))
            return
        }

        onComplete(executeImmediate(command))
    }

    private fun executeImmediate(command: JSONObject): CommandResult {
        return try {
            when (command.optString("type")) {
                "TEXT_INPUT" -> {
                    val text = command.optString("text")
                    if (text.length > MAX_TEXT_LENGTH) return CommandResult(false, "Text is too long")
                    val service = TvRemoteAccessibilityService.instance()
                        ?: return CommandResult(false, accessibilityRequiredMessage())
                    if (service.enterText(text)) CommandResult(true, "Text entered")
                    else CommandResult(false, "No supported editable field is focused")
                }

                "SHARE_TEXT" -> {
                    val text = command.optString("text")
                    if (text.length > MAX_TEXT_LENGTH) return CommandResult(false, "Text is too long")
                    val service = TvRemoteAccessibilityService.instance()
                        ?: return CommandResult(false, accessibilityRequiredMessage())
                    if (service.enterText(text)) CommandResult(true, "Text sent to the focused field")
                    else CommandResult(false, "Open a text field on the TV first")
                }

                "KEY_PRESS" -> {
                    val key = command.optString("key").uppercase()
                    val service = TvRemoteAccessibilityService.instance()
                    if (service == null) {
                        // Accessibility is off (some TV firmware hides it): still let the phone
                        // navigate TeleLink TV itself, including its built-in browser.
                        return if (pressInApp(key)) CommandResult(true, "$key sent to TeleLink TV")
                        else CommandResult(false, accessibilityRequiredMessage())
                    }
                    val handled = when (key) {
                        "SELECT", "ENTER", "OK" -> service.clickFocused()
                        else -> service.press(key)
                    }
                    if (handled) CommandResult(true, "$key sent")
                    else CommandResult(false, "The TV could not apply $key to the current screen")
                }

                "TOUCH_GESTURE" -> {
                    val service = TvRemoteAccessibilityService.instance()
                    val x = command.optDouble("x", Double.NaN).toFloat()
                    val y = command.optDouble("y", Double.NaN).toFloat()
                    val endX = command.optDouble("endX", x.toDouble()).toFloat()
                    val endY = command.optDouble("endY", y.toDouble()).toFloat()
                    if (listOf(x, y, endX, endY).any { !it.isFinite() }) {
                        return CommandResult(false, "Invalid touch coordinates")
                    }
                    val duration = command.optLong("durationMs", 120L)
                    val sent = if (service != null) {
                        service.performTouchGesture(x, y, endX, endY, duration)
                    } else {
                        touchInApp(x, y, endX, endY, duration)
                    }
                    if (sent) CommandResult(true, "Touch gesture sent")
                    else if (service == null) CommandResult(false, accessibilityRequiredMessage())
                    else CommandResult(false, "The TV did not accept the touch gesture")
                }

                "OPEN_URL" -> openUrl(command)
                "LAUNCH_APP" -> launchApp(command.optString("packageName"))
                "LIST_APPS" -> listApps()
                "GET_DEVICE_STATUS" -> CommandResult(true, "Receiver running")
                else -> CommandResult(false, "Unsupported command")
            }
        } catch (exception: Exception) {
            CommandResult(false, exception.message ?: "TV could not perform the command")
        }
    }

    /**
     * Opens the TV's Accessibility settings. Some TV firmware (including Amlogic builds) does not
     * provide the standard screen, so this tries several fallbacks.
     * Returns null when Accessibility settings opened; otherwise a message to show the user.
     */
    fun openAccessibilitySettings(): String? {
        if (autoEnableAccessibility()) return "Phone controls turned on."

        val accessibilityTargets = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.accessibility.AccessibilityActivity"
                )
            )
        )
        if (accessibilityTargets.any(::tryStartSettings)) return null

        // Accessibility is hidden: open Developer options instead, so debugging can be switched on
        // and TeleLink can be allowed from a computer with adb.
        val developerTargets = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.system.development.DevelopmentActivity"
                )
            ),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")
            )
        )
        if (developerTargets.any(::tryStartSettings)) {
            return "Accessibility is hidden on this TV. Opened Developer options: turn on USB debugging or network debugging."
        }

        val generalTargets = listOfNotNull(
            Intent(Settings.ACTION_SETTINGS),
            context.packageManager.getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
            context.packageManager.getLaunchIntentForPackage("com.android.tv.settings")
        )
        return if (generalTargets.any(::tryStartSettings)) {
            "Open Device Preferences > Accessibility, then turn on TeleLink TV remote access."
        } else {
            "This TV hides its Accessibility settings. On a computer run: adb shell pm grant com.telelink.tv android.permission.WRITE_SECURE_SETTINGS, then press this button again."
        }
    }

    /**
     * Turns the TeleLink accessibility service on directly, without the Settings screen. Works once
     * the one-time adb grant of WRITE_SECURE_SETTINGS has been done (see the user guide).
     */
    fun autoEnableAccessibility(): Boolean {
        if (TvRemoteAccessibilityService.instance() != null) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val service = ComponentName(context, TvRemoteAccessibilityService::class.java)
                .flattenToString()
            val resolver = context.contentResolver
            val entries = Settings.Secure
                .getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .filter { it.isNotBlank() }
            if (service !in entries) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (entries + service).joinToString(":")
                )
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a tap or swipe to the TeleLink screen in front (page, browser or app UI). */
    private fun touchInApp(
        x: Float,
        y: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val activity = com.telelink.tv.TeleLinkApplication.resumedActivity() ?: return false
        val decor = activity.window?.decorView ?: return false
        activity.runOnUiThread {
            val width = decor.width.toFloat()
            val height = decor.height.toFloat()
            if (width <= 0f || height <= 0f) return@runOnUiThread
            val startX = x.coerceIn(0f, 1f) * width
            val startY = y.coerceIn(0f, 1f) * height
            val stopX = endX.coerceIn(0f, 1f) * width
            val stopY = endY.coerceIn(0f, 1f) * height
            val duration = durationMs.coerceIn(30L, 1000L)
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, atX: Float, atY: Float, offsetMs: Long) {
                val event = MotionEvent.obtain(downTime, downTime + offsetMs, action, atX, atY, 0)
                decor.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, startX, startY, 0L)
            val moved = kotlin.math.abs(stopX - startX) + kotlin.math.abs(stopY - startY) > 12f
            if (moved) {
                val steps = 6
                for (step in 1..steps) {
                    val fraction = step / steps.toFloat()
                    send(
                        MotionEvent.ACTION_MOVE,
                        startX + (stopX - startX) * fraction,
                        startY + (stopY - startY) * fraction,
                        (duration * fraction).toLong()
                    )
                }
            }
            send(MotionEvent.ACTION_UP, if (moved) stopX else startX, if (moved) stopY else startY, duration)
        }
        return true
    }

    /** Navigates the TeleLink screen that is in front by sending it real key events. */
    private fun pressInApp(key: String): Boolean {
        val activity = com.telelink.tv.TeleLinkApplication.resumedActivity() ?: return false
        return when (key) {
            "HOME" -> try {
                activity.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }

            "BACK" -> {
                activity.runOnUiThread {
                    if (activity is androidx.activity.ComponentActivity) {
                        activity.onBackPressedDispatcher.onBackPressed()
                    } else {
                        activity.finish()
                    }
                }
                true
            }

            else -> {
                val code = when (key) {
                    "UP", "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
                    "DOWN", "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "LEFT", "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "RIGHT", "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "SELECT", "ENTER", "OK" -> KeyEvent.KEYCODE_DPAD_CENTER
                    else -> return false
                }
                activity.runOnUiThread {
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                true
            }
        }
    }

    private fun tryStartSettings(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private fun openUrl(command: JSONObject): CommandResult {
        val rawUrl = command.optString("url").trim()
        if (rawUrl.length > MAX_URL_LENGTH) return CommandResult(false, "URL is too long")
        val uri = rawUrl.toUri()
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return CommandResult(false, "Only valid HTTP and HTTPS URLs are supported")
        }

        return if (command.optString("target", "external") == "integrated") {
            val launched = launchActivity(
                Intent(context, TvBrowserActivity::class.java)
                    .putExtra(TvBrowserActivity.EXTRA_URL, uri.toString())
            )
            if (launched) CommandResult(true, "Opened in TeleLink Browser")
            else CommandResult(false, "TV app is in the background. Select Open request in its notification, or bring TeleLink TV forward and retry.")
        } else {
            val launched = launchActivity(Intent(Intent.ACTION_VIEW, uri))
            if (launched) CommandResult(true, "Opened in the external TV browser")
            else CommandResult(false, "TV app is in the background. Select Open request in its notification, or bring TeleLink TV forward and retry.")
        }
    }

    private fun launchApp(packageName: String): CommandResult {
        if (packageName.isBlank() || packageName.length > 255) {
            return CommandResult(false, "Invalid app package name")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return CommandResult(false, "That app is not installed or cannot be launched")
        return if (launchActivity(launchIntent)) CommandResult(true, "App launched")
        else CommandResult(false, "TV app is in the background. Select Open request in its notification, or bring TeleLink TV forward and retry.")
    }

    private fun launchActivity(intent: Intent): Boolean {
        if (context !is android.app.Activity && !com.telelink.tv.TeleLinkApplication.hasVisibleActivity) {
            onBackgroundLaunchRequested(intent)
            return false
        }
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    @Suppress("DEPRECATION")
    private fun listApps(): CommandResult {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val leanbackApps = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val apps = (if (leanbackApps.isNotEmpty()) leanbackApps else {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        })
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName) return@mapNotNull null
                JSONObject()
                    .put("packageName", activity.packageName)
                    .put("name", resolveInfo.loadLabel(context.packageManager).toString())
            }
            .distinctBy { it.optString("packageName") }
            .sortedBy { it.optString("name").lowercase() }
        val array = org.json.JSONArray().apply { apps.forEach(::put) }
        return CommandResult(
            success = true,
            message = "Found ${apps.size} launchable apps",
            data = JSONObject().put("apps", array)
        )
    }

    private fun accessibilityRequiredMessage(): String =
        "Enable TeleLink TV remote access in Android Accessibility settings first"

    private companion object {
        const val MAX_TEXT_LENGTH = 8192
        const val MAX_URL_LENGTH = 4096
    }
}
