package com.calypsan.listenup.client.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [NetworkMonitor] using ConnectivityManager.
 *
 * Registers a network callback to track connectivity changes in real-time.
 * Checks for internet capability (not just network connection) to ensure
 * actual connectivity.
 *
 * Lifecycle: Should be created once and kept alive for the app's lifetime.
 * The network callback is registered in init and never unregistered since
 * we want continuous monitoring.
 *
 * @param context Application context for accessing ConnectivityManager
 */
@SuppressLint("MissingPermission") // Permission declared in app module manifest
class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val _isOnlineFlow = MutableStateFlow(checkCurrentConnectivity())
    override val isOnlineFlow: StateFlow<Boolean> = _isOnlineFlow.asStateFlow()

    private val _isOnUnmeteredNetworkFlow = MutableStateFlow(checkCurrentUnmetered())
    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = _isOnUnmeteredNetworkFlow.asStateFlow()

    init {
        val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnlineFlow.value = true
                    _isOnUnmeteredNetworkFlow.value = checkCurrentUnmetered()
                }

                override fun onLost(network: Network) {
                    // Re-check since there might be other networks available
                    _isOnlineFlow.value = checkCurrentConnectivity()
                    _isOnUnmeteredNetworkFlow.value = checkCurrentUnmetered()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val hasInternet =
                        capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        ) &&
                            capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                            )
                    _isOnlineFlow.value = hasInternet

                    // Check if network is unmetered (WiFi, ethernet, etc.)
                    val isUnmetered =
                        hasInternet &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    _isOnUnmeteredNetworkFlow.value = isUnmetered
                }
            }

        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun isOnline(): Boolean = _isOnlineFlow.value

    /**
     * Check current connectivity state.
     *
     * Used for initial state and when a network is lost to check
     * if other networks are still available.
     */
    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Check if currently on an unmetered network (WiFi, ethernet).
     *
     * Used to determine if downloads can proceed when WiFi-only is enabled.
     */
    private fun checkCurrentUnmetered(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
