package com.calypsan.listenup.api

/**
 * HTTP header names for the bidirectional client↔server version exchange. One definition, both
 * sides read it, so a wire-name typo across the boundary can't silently break version exchange.
 */
object VersionHeaders {
    /** Client → server: the client build's semver. */
    const val CLIENT_VERSION = "X-Client-Version"

    /** Client → server: the API contract version the client expects. */
    const val CLIENT_API = "X-Client-Api"

    /** Server → client: the server build's semver. */
    const val SERVER_VERSION = "X-Server-Version"

    /** Server → client: the API contract version the server speaks. */
    const val SERVER_API = "X-Server-Api"
}
