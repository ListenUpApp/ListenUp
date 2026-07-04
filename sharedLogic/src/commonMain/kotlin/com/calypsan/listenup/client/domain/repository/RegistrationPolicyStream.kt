package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import kotlinx.coroutines.flow.Flow

/**
 * Live stream of the server's instance-wide [RegistrationPolicy] for the pre-auth login screen.
 *
 * A client sitting on the login screen collects this to keep its Sign Up affordance honest: the
 * moment an admin closes (or reopens) registration, the flow emits the new policy and the screen
 * flips without a relaunch. It is the live channel that [AuthSession.refreshOpenRegistration]'s
 * one-shot fetch (via `getServerInfo`) only approximates — that one-shot stays as the never-stranded
 * fallback for clients where the SSE stream doesn't deliver.
 *
 * `internal`: the interface, its SSE implementation, and its sole consumer ([AuthSessionStore]) all
 * live in `:sharedLogic`, so it never needs to cross a module boundary or reach the export surface.
 */
internal interface RegistrationPolicyStream {
    /**
     * Stream the instance-wide registration policy: the current value on connect, then each change.
     *
     * Uses SSE. The flow runs for the connection's lifetime; the caller collects it only while the
     * login screen is showing and cancels otherwise.
     */
    fun streamPolicy(): Flow<RegistrationPolicy>
}
