package com.telelink.tv.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

class TvRemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    fun enterText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun press(key: String): Boolean {
        when (key.uppercase()) {
            "BACK" -> return performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> return performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> return performGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        val direction = when (key.uppercase()) {
            "UP", "DPAD_UP" -> View.FOCUS_UP
            "DOWN", "DPAD_DOWN" -> View.FOCUS_DOWN
            "LEFT", "DPAD_LEFT" -> View.FOCUS_LEFT
            "RIGHT", "DPAD_RIGHT" -> View.FOCUS_RIGHT
            else -> return false
        }
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val next = focused?.focusSearch(direction)
            ?: root.focusSearch(direction)
            ?: return false
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    fun clickFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun performTouchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long
    ): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(startX.coerceIn(0f, 1f) * metrics.widthPixels,
                startY.coerceIn(0f, 1f) * metrics.heightPixels)
            lineTo(endX.coerceIn(0f, 1f) * metrics.widthPixels,
                endY.coerceIn(0f, 1f) * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMillis.coerceIn(50L, 1000L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        private var current: TvRemoteAccessibilityService? = null

        fun instance(): TvRemoteAccessibilityService? = current
    }
}
