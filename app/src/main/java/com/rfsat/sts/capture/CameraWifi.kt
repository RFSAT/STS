package com.rfsat.sts.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bind the process to a camera's internet-less Wi-Fi AP so HTTP goes over the
 * camera link, not mobile data — the shared version of the routine CaptureActivity
 * uses, so other screens (e.g. Score → GoPro photo) can reuse it. [onReady] is
 * posted to the main thread with the network, or null after an 8 s fallback.
 */
object CameraWifi {
    fun acquire(context: Context, onReady: (Network?) -> Unit): ConnectivityManager.NetworkCallback {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val started = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun begin(net: Network?) {
            if (!started.compareAndSet(false, true)) return
            runCatching { if (net != null) cm.bindProcessToNetwork(net) }
            main.post { onReady(net) }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = begin(network)
        }
        runCatching { cm.requestNetwork(req, cb) }.onFailure { begin(null) }
        val timeoutMs = if (com.rfsat.sts.ui.RangeSettings.autoReconnect()) 20000L else 8000L
        main.postDelayed({ begin(null) }, timeoutMs)
        return cb
    }

    fun release(context: Context, cb: ConnectivityManager.NetworkCallback?) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.bindProcessToNetwork(null) }
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}
