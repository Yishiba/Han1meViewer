package com.yenaly.han1meviewer.logic.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** One process-wide callback, registered only while translation views are observing it. */
object TranslationNetwork {
    @Volatile private var shared: StateFlow<Int>? = null
    fun observe(context: Context): StateFlow<Int> = shared ?: synchronized(this) {
        shared ?: run {
            val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            fun status(): Int {
                val network = manager.getNetworkCapabilities(manager.activeNetwork) ?: return 0
                return if (network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1
            }
            callbackFlow {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(status()) }
                    override fun onLost(network: Network) { trySend(status()) }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(status()) }
                }
                manager.registerDefaultNetworkCallback(callback)
                trySend(status())
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged().stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default),
                SharingStarted.WhileSubscribed(5000), status())
        }.also { shared = it }
    }
}
