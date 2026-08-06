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

class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
    }
    fun requestNetwork(request: NetworkRequest, cb: NetworkCallback) {}
    fun unregisterNetworkCallback(cb: NetworkCallback) {}
    fun bindProcessToNetwork(network: Network?): Boolean = true
}
