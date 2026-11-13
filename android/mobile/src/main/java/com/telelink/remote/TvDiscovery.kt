package com.telelink.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    internal val sessionId: Long
)

internal fun selectReceiverAddress(addresses: Iterable<InetAddress>): Inet4Address? =
    addresses.firstOrNull { it is Inet4Address } as? Inet4Address

class TvDiscovery(context: Context) {
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val callbackExecutor = Executor { it.run() }
    private var listener: NsdManager.DiscoveryListener? = null
    private val generation = DiscoveryGeneration()

    @Synchronized
    fun start(
        onFound: (DiscoveredTv) -> Unit,
        onFailure: (String) -> Unit,
        onLost: (String, Long) -> Unit = { _, _ -> }
    ): Long {
        if (listener != null) return generation.current()
        val activeGeneration = generation.begin()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!generation.isCurrent(activeGeneration)) return
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    monitorService(serviceInfo, activeGeneration, onFound, onFailure, onLost)
                } else {
                    resolveLegacy(serviceInfo, activeGeneration, onFound)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (generation.isCurrent(activeGeneration) &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ) {
                    onLost(serviceInfo.serviceName, activeGeneration)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!generation.isCurrent(activeGeneration)) return
                onFailure("TV discovery failed (code $errorCode)")
                stop()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!generation.isCurrent(activeGeneration)) return
                onFailure("Could not stop TV discovery (code $errorCode)")
                stop()
            }
        }
        listener = discoveryListener
        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (exception: Exception) {
            if (generation.isCurrent(activeGeneration)) {
                listener = null
                onFailure(exception.message ?: "TV discovery failed")
            }
        }
        return activeGeneration
    }

    // API 34 exposes these callbacks as platform APIs; older devices use resolveService below.
    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun monitorService(
        serviceInfo: NsdServiceInfo,
        activeGeneration: Long,
        onFound: (DiscoveredTv) -> Unit,
        onFailure: (String) -> Unit,
        onLost: (String, Long) -> Unit
    ) {
        val serviceName = serviceInfo.serviceName
        if (infoCallbacks.containsKey(serviceName)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                if (!generation.isCurrent(activeGeneration)) return
                val host = selectReceiverAddress(info.hostAddresses) ?: return
                reportResolvedService(info, host, activeGeneration, onFound)
            }

            override fun onServiceLost() {
                if (!generation.isCurrent(activeGeneration)) return
                infoCallbacks.remove(serviceName, this)
                onLost(serviceName, activeGeneration)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (!generation.isCurrent(activeGeneration)) return
                infoCallbacks.remove(serviceName, this)
                onFailure("Could not resolve TV discovery record (code $errorCode)")
            }

            override fun onServiceInfoCallbackUnregistered() {
                infoCallbacks.remove(serviceName, this)
            }
        }
        if (infoCallbacks.putIfAbsent(serviceName, callback) != null) return
        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
            if (!generation.isCurrent(activeGeneration)) {
                infoCallbacks.remove(serviceName, callback)
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
        } catch (exception: Exception) {
            if (generation.isCurrent(activeGeneration)) {
                infoCallbacks.remove(serviceName, callback)
                onFailure(exception.message ?: "Could not resolve TV discovery record")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        serviceInfo: NsdServiceInfo,
        activeGeneration: Long,
        onFound: (DiscoveredTv) -> Unit
    ) {
        if (!resolving.add(serviceInfo.serviceName)) return
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (generation.isCurrent(activeGeneration)) resolving.remove(info.serviceName)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (!generation.isCurrent(activeGeneration)) return
                resolving.remove(info.serviceName)
                val host = selectReceiverAddress(listOfNotNull(info.host)) ?: return
                reportResolvedService(info, host, activeGeneration, onFound)
            }
        })
    }

    private fun reportResolvedService(
        info: NsdServiceInfo,
        host: InetAddress,
        activeGeneration: Long,
        onFound: (DiscoveredTv) -> Unit
    ) {
        if (!generation.isCurrent(activeGeneration)) return
        val protocol = info.attributes[ATTRIBUTE_PROTOCOL]
            ?.toString(Charsets.UTF_8)
            ?: return
        if (protocol != SUPPORTED_PROTOCOL) return
        val fingerprint = info.attributes[ATTRIBUTE_FINGERPRINT]
            ?.toString(Charsets.UTF_8)
            ?.uppercase()
            ?: return
        if (!fingerprint.matches(Regex("[A-F0-9]{64}"))) return
        onFound(
            DiscoveredTv(
                name = info.serviceName,
                host = host.hostAddress ?: return,
                port = info.port,
                fingerprint = fingerprint,
                sessionId = activeGeneration
            )
        )
    }

    @Synchronized
    fun stop() {
        generation.invalidate()
        val current = listener
        listener = null
        resolving.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterServiceInfoCallbacks()
        }
        infoCallbacks.clear()
        if (current == null) return
        try {
            nsdManager.stopServiceDiscovery(current)
        } catch (_: Exception) {
            // Android may already have stopped this listener.
        }
    }

    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterServiceInfoCallbacks() {
        infoCallbacks.values.toList().forEach { callback ->
            try {
                nsdManager.unregisterServiceInfoCallback(callback)
            } catch (_: Exception) {
                // Android may already have released the callback.
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_telelink._tcp."
        private const val ATTRIBUTE_PROTOCOL = "protocol"
        private const val SUPPORTED_PROTOCOL = "1"
        private const val ATTRIBUTE_FINGERPRINT = "fingerprint"
    }
}
