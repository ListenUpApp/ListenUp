package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single-use, seconds-lived handle that authenticates exactly one WebSocket upgrade.
 *
 * Exists because a browser cannot put an `Authorization` header on a WebSocket upgrade — the DOM
 * `WebSocket` constructor is `(url, protocols)` — which leaves the URL as the only carrier, and a
 * URL is logged by every reverse proxy a self-hoster is likely to run. A ticket is what makes that
 * survivable: by the time anyone reads the log line it is spent and expired.
 *
 * It stands in for an access token rather than replacing one. Redeeming it yields the original JWT,
 * which is then verified exactly as a header-borne token is — so a ticket grants nothing its bearer
 * did not already hold, and grants it for one connection.
 */
@Serializable
data class SocketTicket(
    @SerialName("value")
    val value: String,
)
