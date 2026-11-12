package com.lastwave.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class ActiveRoute(
        val network: Network? = null,
        val isCellular: Boolean = false,
        val downstreamBandwidthKbps: Int? = null,
        val isUnmetered: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Synchronous check on whether a usable internet connection is present.
     */
    fun isCurrentlyConnected(): Boolean = runCatching {
        val cm = connectivityManager ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /**
     * Synchronous check for the cellular transport on the active network.
     */
    fun isOnCellular(): Boolean = runCatching {
        val cm = connectivityManager ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    private fun readActiveRoute(): ActiveRoute = runCatching {
        val cm = connectivityManager ?: return@runCatching null
        val activeNetwork = cm.activeNetwork ?: return@runCatching null
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching null
        capabilities.toActiveRoute(activeNetwork)
    }.getOrNull() ?: ActiveRoute()

    private fun NetworkCapabilities.toActiveRoute(network: Network) = ActiveRoute(
        network = network,
        isCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        downstreamBandwidthKbps = linkDownstreamBandwidthKbps.takeIf { it > 0 },
        isUnmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    fun isUnmetered(): Boolean = activeRoute.value.isUnmetered

    /** True when Android routes the active network through a VPN transport. */
    fun isOnVpn(): Boolean = runCatching {
        val cm = connectivityManager ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    /**
     * Hot reactive state flow emitting true when online and false when offline.
     */
    val isOnline: StateFlow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isCurrentlyConnected())
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyConnected())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                trySend(hasInternet && isCurrentlyConnected())
            }
        }

        trySend(isCurrentlyConnected())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = isCurrentlyConnected(),
        )

    /**
     * Observe transport and link estimate together. A transport handoff can
     * have the same reported bandwidth and still change the quality profile.
     */
    val activeRoute: StateFlow<ActiveRoute> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(ActiveRoute())
            awaitClose { }
            return@callbackFlow
        }

        val initialRoute = readActiveRoute()
        var defaultNetwork = initialRoute.network
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    trySend(ActiveRoute())
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (defaultNetwork == network) {
                    trySend(networkCapabilities.toActiveRoute(network))
                }
            }
        }

        trySend(initialRoute)
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = readActiveRoute(),
        )

    /** Hot state that lets artwork switch resolution when the active transport changes. */
    val isCellular: StateFlow<Boolean> = activeRoute
        .map { it.isCellular }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = activeRoute.value.isCellular,
        )
}
