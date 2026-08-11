package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies the server instance to clients on first connect.
 *
 * This is the canonical example of a wire DTO: defined once in commonMain,
 * serialized identically by client and server, never duplicated. Fetched before
 * authentication to verify a URL points at a ListenUp server and to route the
 * onboarding flow — [setupRequired] decides setup-vs-login, [registrationPolicy]
 * decides whether to offer self-registration (and on what terms).
 */
@Serializable
data class ServerInfo(
    /** Human-readable server name, e.g. "ListenUp". */
    @SerialName("name")
    val name: String,
    /** Server build version, e.g. "0.0.1". */
    @SerialName("version")
    val version: String,
    /** API contract version this server speaks, e.g. "v1". */
    @SerialName("apiVersion")
    val apiVersion: String,
    /** True when no users exist yet — the client routes to first-run root setup. */
    @SerialName("setupRequired")
    val setupRequired: Boolean,
    /** Live self-registration policy; gates the "Create Account" affordance (shown when not [RegistrationPolicy.CLOSED]). */
    @SerialName("registrationPolicy")
    val registrationPolicy: RegistrationPolicy,
    /** Operator-set remote (WAN) URL for off-LAN access; null when unset. Clients persist it as the reconnect fallback. */
    @SerialName("remoteUrl")
    val remoteUrl: String? = null,
    /** Whether this server can send push notifications (admin toggle AND relay configured). */
    @SerialName("pushEnabled")
    val pushEnabled: Boolean = false,
    /**
     * Stable server-instance id (the mDNS TXT `id`), persisted server-side so it survives restarts.
     * Clients compare it on reconnect to tell a restart of the *same* server (stay signed in) from a
     * *different* server (clean re-auth).
     */
    @SerialName("instanceId")
    val instanceId: String,
)
