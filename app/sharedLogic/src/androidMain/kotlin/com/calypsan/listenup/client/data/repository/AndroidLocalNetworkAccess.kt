package com.calypsan.listenup.client.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

private val logger = KotlinLogging.logger {}

/**
 * The first API level that enforces `ACCESS_LOCAL_NETWORK` for an app targeting it (Android 17).
 * API 36 declares the permission but does not block connections without it.
 */
private const val LOCAL_NETWORK_ENFORCED_API = 37

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
        if (sdkInt < LOCAL_NETWORK_ENFORCED_API || isGranted()) return false
        if (isLocalNetworkHost(host) || isAddressLiteral(host)) return isLocalNetworkHost(host)
        return resolveHost(host).any(::isLocalNetworkHost)
    }

    private fun isAddressLiteral(host: String): Boolean = ':' in host || host.all { it.isDigit() || it == '.' }
}

/** Every address [host] resolves to, off the main thread; empty when it does not resolve. */
private suspend fun resolveAddresses(host: String): List<String> =
    withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        } catch (e: UnknownHostException) {
            logger.debug(e) { "$host does not resolve; the local-network gate cannot be what blocked it" }
            emptyList()
        }
    }
