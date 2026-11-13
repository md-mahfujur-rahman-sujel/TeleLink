package com.telelink.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class TvServicePublisher(context: Context) {
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(fingerprint: String, onFailure: (String) -> Unit) {
        if (registrationListener != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (clearIfCurrent(this)) {
                    onFailure("TV discovery registration failed (code $errorCode)")
                }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (clearIfCurrent(this)) {
                    onFailure("TV discovery could not stop cleanly (code $errorCode)")
                }
            }
        }
        registrationListener = listener
        val service = NsdServiceInfo().apply {
            serviceName = "TeleLink TV"
            serviceType = SERVICE_TYPE
            port = RECEIVER_PORT
            setAttribute("protocol", "1")
            setAttribute("fingerprint", fingerprint)
        }
        try {
            nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (exception: Exception) {
            registrationListener = null
            throw exception
        }
    }

    @Synchronized
    fun stop() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (_: Exception) {
            // The registration may already have been removed by Android.
        }
    }

    @Synchronized
    private fun clearIfCurrent(listener: NsdManager.RegistrationListener): Boolean {
        if (registrationListener !== listener) return false
        registrationListener = null
        return true
    }

    companion object {
        const val SERVICE_TYPE = "_telelink._tcp."
        const val RECEIVER_PORT = 8765
    }
}
