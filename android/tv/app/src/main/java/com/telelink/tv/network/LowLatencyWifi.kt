package com.telelink.tv.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio out of power-save mode while the receiver is running. Power-save is a
 * common cause of remote commands arriving in delayed bursts. Harmless on Ethernet.
 */
@Suppress("DEPRECATION")
class LowLatencyWifi(context: Context) {
    private val lock: WifiManager.WifiLock? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifi?.createWifiLock(mode, "TeleLink:receiver")?.also { it.setReferenceCounted(false) }
    } catch (_: Exception) {
        null
    }

    fun acquire() {
        try {
            lock?.let { if (!it.isHeld) it.acquire() }
        } catch (_: Exception) {
            // Best effort: lack of the lock only means slightly higher latency.
        }
    }

    fun release() {
        try {
            lock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }
}
