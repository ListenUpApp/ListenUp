package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Domain model for streamed registration status updates.
 *
 * This is distinct from [RegistrationStatus] (used for polling)
 * as it provides a sealed hierarchy for type-safe when expressions.
 */
sealed interface StreamedRegistrationStatus {
    /** Registration is still pending admin approval. */
    data object Pending : StreamedRegistrationStatus

    /** Registration has been approved. */
    data object Approved : StreamedRegistrationStatus

    /** Registration has been denied. */
    data class Denied(
        val message: String?,
    ) : StreamedRegistrationStatus
}

/**
 * Stream for monitoring registration approval status.
 *
 * Provides real-time updates on whether a pending registration
 * has been approved or denied by an administrator.
 */
interface RegistrationStatusStream {
    /**
     * Stream registration status updates for the given user.
     *
     * Rides the public-channel RPC watch (`AuthServicePublic.observeRegistrationStatus`): emits the
     * current status, then live updates, and COMPLETES once the status turns terminal — normal flow
     * completion is the terminal signal, not a dropped connection to reconnect.
     *
     * @param userId The user ID to monitor
     * @return Flow of streamed registration status updates; completes on a terminal status
     * @throws Exception if the underlying watch surfaces a typed business failure (e.g. an
     *   unrecognised registration id) or the stream transport fails
     */
    fun streamStatus(userId: String): Flow<StreamedRegistrationStatus>

    /**
     * One-shot pull of the registrant's current status — the first emission of the same RPC watch
     * [streamStatus] rides.
     *
     * The "never stranded" fallback for clients where [streamStatus] doesn't deliver: "Check
     * Status", an on-screen poll, and the on-entry check all use this so an approved registrant
     * always learns it. Never throws — any transport/parse/business failure resolves to
     * [StreamedRegistrationStatus.Pending] so the screen safely stays in its waiting state.
     *
     * @param userId The user ID to check
     * @return the current status; [StreamedRegistrationStatus.Pending] on any failure
     */
    suspend fun fetchStatus(userId: String): StreamedRegistrationStatus
}
