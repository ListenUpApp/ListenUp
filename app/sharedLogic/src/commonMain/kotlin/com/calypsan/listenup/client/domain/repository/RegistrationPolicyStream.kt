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
 * fallback for clients where the live watch doesn't deliver.
 *
 * `internal`: the interface, its RPC implementation, and its sole consumer ([AuthSessionStore]) all
 * live in `:app:sharedLogic`, so it never needs to cross a module boundary or reach the export surface.
 */
internal interface RegistrationPolicyStream {
    /**
     * Stream the instance-wide registration policy: the current value on subscribe, then each
     * change.
     *
     * Rides the public RPC channel and never completes on its own — the implementation resubscribes
     * a dropped watch; the caller collects only while the login screen is showing and cancels
     * otherwise.
     */
    fun streamPolicy(): Flow<RegistrationPolicy>
}
