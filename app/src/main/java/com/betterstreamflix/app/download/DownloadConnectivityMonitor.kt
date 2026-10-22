package com.betterstreamflix.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadNetworkType {
    NONE,
    WIFI,
    CELLULAR,
    OTHER,
}

object DownloadConnectivityMonitor {
    data class Status(
        val type: DownloadNetworkType,
        val validated: Boolean,
        val isMetered: Boolean = false,
    )

    private val _status = MutableStateFlow(Status(DownloadNetworkType.NONE, false, false))
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        refresh(cm)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh(cm)
                override fun onLost(network: Network) = refresh(cm)
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = refresh(cm)
            },
        )
        registered = true
    }

    fun current(context: Context): Status {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        refresh(cm)
        return _status.value
    }

    fun isWifi(context: Context): Boolean = current(context).type == DownloadNetworkType.WIFI

    fun isMetered(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.isActiveNetworkMetered
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE
        }
    }

    private fun refresh(cm: ConnectivityManager) {
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork
        } else {
            null
        }
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            caps == null -> DownloadNetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DownloadNetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DownloadNetworkType.CELLULAR
            else -> DownloadNetworkType.OTHER
        }
        val metered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.isActiveNetworkMetered
        } else {
            type == DownloadNetworkType.CELLULAR
        }
        _status.value = Status(type = type, validated = validated, isMetered = metered)
    }

    /** True when any network is up — lets UI distinguish offline vs provider errors. */
    fun isOnline(context: Context): Boolean =
        current(context).type != DownloadNetworkType.NONE

    /**
     * Product rule for Wi‑Fi-only downloads: hold when the preference is on and the
     * active network is metered (cellular, hotspot, or other metered transports).
     * When a Wi‑Fi window is enabled, also hold outside that local minute-of-day range.
     * Shared by the connectivity watcher, enqueue/resume gates, Smart Downloads, and UI banner.
     */
    fun shouldHoldForWifiOnly(context: Context): Boolean {
        if (!UserPreferences.downloadWifiOnly) return false
        if (isMetered(context)) return true
        if (!UserPreferences.downloadWifiWindowEnabled) return false
        return !isInsideWifiWindow()
    }

    /** True when local clock minute-of-day is inside the configured Wi‑Fi window. */
    fun isInsideWifiWindow(
        minuteOfDay: Int = currentMinuteOfDay(),
    ): Boolean {
        if (!UserPreferences.downloadWifiWindowEnabled) return true
        val start = UserPreferences.downloadWifiWindowStartMinute
        val end = UserPreferences.downloadWifiWindowEndMinute
        if (start == end) return true
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            // Overnight window (e.g. 22:00–07:00).
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    private fun currentMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }
}
