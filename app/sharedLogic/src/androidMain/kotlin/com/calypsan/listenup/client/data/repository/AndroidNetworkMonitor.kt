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

/**
 * The connectivity facts [AndroidNetworkMonitor] reads off the system's **active** network.
 *
 * [isValidated] is recorded but deliberately NOT used by [NetworkStatusPolicy] — see its KDoc.
 * Carrying it keeps the omission explicit rather than invisible.
 */
internal data class NetworkFacts(
    val hasInternetRoute: Boolean,
    val isValidated: Boolean,
    val isUnmetered: Boolean,
) {
    companion object {
        /** No active network at all. */
        val NONE = NetworkFacts(hasInternetRoute = false, isValidated = false, isUnmetered = false)
    }
}

/**
 * Turns [NetworkFacts] into the two booleans [NetworkMonitor] exposes. Pure, so the decision is
 * testable without a `ConnectivityManager` (`:app:sharedLogic`'s androidHostTest lane has no
 * Robolectric).
 *
 * **`NET_CAPABILITY_VALIDATED` is deliberately not required.** Android sets it only after its
 * captive-portal probe reaches the public internet. ListenUp servers are self-hosted, and the
 * flagship case is a server on the user's own LAN — where a WAN-less, air-gapped, captive or
 * probe-blocked network never validates. Gating on it made the client declare itself offline
 * while the server was reachable on the same subnet: no sync drain, no foreground catch-up, no
 * repository fetch-on-miss. `NET_CAPABILITY_INTERNET` ("this network offers a route") is the
 * honest signal, and it matches `AppleNetworkMonitor`'s `nw_path_status_satisfied`, so the two
 * platforms finally mean the same thing by "online".
 *
 * Whether the *server* is actually reachable is decided downstream by
 * [com.calypsan.listenup.client.data.connection.ConnectionHealthStore], which is evidence-based;
 * a route with nothing behind it costs one failed RPC, not a wedged client.
 */
internal object NetworkStatusPolicy {
    fun isOnline(facts: NetworkFacts): Boolean = facts.hasInternetRoute

    fun isUnmetered(facts: NetworkFacts): Boolean = facts.hasInternetRoute && facts.isUnmetered
}

/**
 * Android implementation of [NetworkMonitor] using ConnectivityManager.
 *
 * Registers a network callback and re-derives both flows from the system's **active** network on
 * every event. "Online" means the active network offers an internet ROUTE
 * (`NET_CAPABILITY_INTERNET`) — deliberately not that Android validated it; see
 * [NetworkStatusPolicy].
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

    override val isOnlineFlow: StateFlow<Boolean>
        field = MutableStateFlow(NetworkStatusPolicy.isOnline(activeNetworkFacts()))

    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean>
        field = MutableStateFlow(NetworkStatusPolicy.isUnmetered(activeNetworkFacts()))

    init {
        val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                // All three callbacks re-derive from the ACTIVE network rather than from the
                // network the callback happens to be about. On a device holding Wi-Fi plus
                // cellular or a VPN, a capability change on the network the app is NOT using
                // used to overwrite the process-wide answer.
                override fun onAvailable(network: Network) = refresh()

                override fun onLost(network: Network) = refresh()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = refresh()
            }

        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun isOnline(): Boolean = isOnlineFlow.value

    /** Re-derive both flows from the system's currently active network. */
    private fun refresh() {
        val facts = activeNetworkFacts()
        isOnlineFlow.value = NetworkStatusPolicy.isOnline(facts)
        isOnUnmeteredNetworkFlow.value = NetworkStatusPolicy.isUnmetered(facts)
    }

    /** The [NetworkFacts] of the system's active network, or [NetworkFacts.NONE] if there is none. */
    private fun activeNetworkFacts(): NetworkFacts {
        val network = connectivityManager.activeNetwork ?: return NetworkFacts.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkFacts.NONE
        return NetworkFacts(
            hasInternetRoute = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
