package com.calypsan.listenup.client.data.discovery

import platform.Foundation.NSNetServicesErrorCode
import platform.Foundation.NSNumber

/**
 * `kDNSServiceErr_PolicyDenied` — the DNS-SD error a Bonjour browse fails with when the user has
 * not allowed ListenUp onto the local network (Apple TN3179).
 */
internal const val DNS_SERVICE_POLICY_DENIED = -65570

/**
 * True when an `NSNetServiceBrowser` `didNotSearch` error dictionary says the browse was refused
 * by Local Network privacy, rather than failing for any other reason.
 */
internal fun isBonjourPolicyDenial(errorDict: Map<Any?, *>): Boolean {
    val code =
        when (val value = errorDict[NSNetServicesErrorCode]) {
            is Number -> value.toInt()
            is NSNumber -> value.intValue
            else -> null
        }
    return code == DNS_SERVICE_POLICY_DENIED
}
