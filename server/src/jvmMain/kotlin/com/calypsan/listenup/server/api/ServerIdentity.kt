package com.calypsan.listenup.server.api

/** Single source of the server's advertised identity — read by InstanceService (getServerInfo) and mDNS. */
internal object ServerIdentity {
    const val NAME = "ListenUp"
    const val VERSION = "0.0.1"
    const val API_VERSION = "v1"
}
