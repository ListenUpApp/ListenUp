package com.calypsan.listenup.client.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import java.net.UnknownHostException

private val logger = KotlinLogging.logger {}

/** How long a DNS lookup may take before the host is treated as unresolved. */
private val RESOLVE_TIMEOUT = 2.seconds

/**
 * Android's [LocalNetworkAccess]: a connection is blocked by the gate when the platform enforces
 * it, the user has not granted `ACCESS_LOCAL_NETWORK`, and the host is on the local network.
 *
 * Android gives no signal on the connection itself — a denied connect simply times out — so the
 * verdict is inferred from those three facts. For a plain hostname (`nas.lan`) the addresses are
 * resolved first: DNS is exempt from the permission, and only the address says whether the gate
 * applies. An mDNS `.local` name is classified directly, since resolving it is itself gated.
 *
 * The facts are injected so the decision runs on the host JVM; production uses the
 * [Context] constructor.
 */
internal class AndroidLocalNetworkAccess internal constructor(
    private val sdkInt: Int,
    private val isGranted: () -> Boolean,
    private val resolveHost: suspend (String) -> List<String>,
) : LocalNetworkAccess {
    constructor(context: Context) : this(
        sdkInt = Build.VERSION.SDK_INT,
        isGranted = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        },
        resolveHost = ::resolveAddresses,
    )

    override suspend fun isDeniedFor(
        host: String,
        port: Int,
    ): Boolean {
        if (sdkInt < LocalNetworkPermissionApi.ENFORCED || isGranted()) return false
        if (isLocalNetworkHost(host) || isAddressLiteral(host)) return isLocalNetworkHost(host)
        // A resolver that never answers must not hold the connect screen: past the bound, the host
        // is treated as unresolved, which never blames the permission.
        val addresses = withTimeoutOrNull(RESOLVE_TIMEOUT) { resolveHost(host) } ?: emptyList()
        return addresses.any(::isLocalNetworkHost)
    }

    private fun isAddressLiteral(host: String): Boolean = ':' in host || host.all { it.isDigit() || it == '.' }
}

/**
 * Every address [host] resolves to, off the main thread; empty when it does not resolve.
 * `getAllByName` blocks in the system resolver, so it runs interruptibly: the caller's timeout
 * interrupts the thread instead of waiting out the resolver's own.
 */
private suspend fun resolveAddresses(host: String): List<String> =
    runInterruptible(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        } catch (e: UnknownHostException) {
            logger.debug(e) { "$host does not resolve; the local-network gate cannot be what blocked it" }
            emptyList()
        }
    }
