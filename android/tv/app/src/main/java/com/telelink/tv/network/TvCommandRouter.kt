package com.telelink.tv.network

import android.content.Context
import android.content.Intent
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
                        ?: return CommandResult(false, accessibilityRequiredMessage())
                    val handled = when (key) {
                        "SELECT", "ENTER", "OK" -> service.clickFocused()
                        else -> service.press(key)
                    }
                    if (handled) CommandResult(true, "$key sent")
                    else CommandResult(false, "The TV could not apply $key to the current screen")
                }

                "TOUCH_GESTURE" -> {
                    val service = TvRemoteAccessibilityService.instance()
                        ?: return CommandResult(false, accessibilityRequiredMessage())
                    val x = command.optDouble("x", Double.NaN).toFloat()
                    val y = command.optDouble("y", Double.NaN).toFloat()
                    val endX = command.optDouble("endX", x.toDouble()).toFloat()
                    val endY = command.optDouble("endY", y.toDouble()).toFloat()
                    if (listOf(x, y, endX, endY).any { !it.isFinite() }) {
                        return CommandResult(false, "Invalid touch coordinates")
                    }
                    val sent = service.performTouchGesture(x, y, endX, endY, command.optLong("durationMs", 120L))
                    if (sent) CommandResult(true, "Touch gesture sent")
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

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
