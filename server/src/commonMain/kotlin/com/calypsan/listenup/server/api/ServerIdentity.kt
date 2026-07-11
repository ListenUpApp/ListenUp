package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.EXPECTED_API_VERSION

/** Single source of the server's advertised identity — read by InstanceService (getServerInfo) and mDNS. */
internal object ServerIdentity {
    const val NAME = "ListenUp"

    /** Build-injected from the repo-root VERSION file (see generateServerVersion in server/build.gradle.kts). */
    const val VERSION = SERVER_VERSION

    /** The shared contract API version, so client and server read one source. */
    const val API_VERSION = EXPECTED_API_VERSION
}
