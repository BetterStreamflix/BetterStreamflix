package com.dskja.betterstreamflix.logo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cancels Featured logo enrich when the device leaves unmetered Wi‑Fi.
 */
object FeaturedNetworkMonitor {

    fun interface Listener {
        fun onBecameMetered()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var registered = false
    @Volatile private var lastMetered: Boolean? = null

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val metered = !capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                    )
                    val prev = lastMetered
                    lastMetered = metered
                    if (metered && prev == false) {
                        listeners.forEach { it.onBecameMetered() }
                    }
                }

                override fun onLost(network: Network) {
                    // Treat loss as potentially metered / unknown — cancel enrich.
                    listeners.forEach { it.onBecameMetered() }
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(callback)
                } else {
                    cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
                }
                registered = true
            }
        }
    }
}
