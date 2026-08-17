package android.net

/** The camera's access point, as Android hands it over. */
class Network {
    val socketFactory: javax.net.SocketFactory = javax.net.SocketFactory.getDefault()
}

class NetworkCapabilities {
    companion object {
        const val TRANSPORT_WIFI = 1
        const val NET_CAPABILITY_INTERNET = 12
    }
}

class NetworkRequest {
    class Builder {
        fun addTransportType(t: Int): Builder = this
        fun removeCapability(c: Int): Builder = this
        fun build(): NetworkRequest = NetworkRequest()
    }
}

/** Enough of the routing table to find the camera's gateway. */
class RouteInfo {
    val isDefaultRoute: Boolean = false
    val gateway: java.net.InetAddress? = null
}

class LinkProperties {
    val routes: List<RouteInfo> = emptyList()
}

class ConnectivityManager {
    fun getLinkProperties(network: Network?): LinkProperties? = null
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
    }
    fun requestNetwork(request: NetworkRequest, cb: NetworkCallback) {}
    fun unregisterNetworkCallback(cb: NetworkCallback) {}
    fun bindProcessToNetwork(network: Network?): Boolean = true
}
