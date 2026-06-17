package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Domain model for SSE-streamed registration status updates.
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
     * Uses SSE (Server-Sent Events) for real-time updates.
     * The flow will emit status changes as they occur.
     *
     * @param userId The user ID to monitor
     * @return Flow of streamed registration status updates
     * @throws Exception if the connection fails
     */
    fun streamStatus(userId: String): Flow<StreamedRegistrationStatus>

    /**
     * One-shot pull of the registrant's current status via a plain request/response GET.
     *
     * The "never stranded" fallback for clients where the SSE [streamStatus] doesn't deliver
     * (notably the iOS Darwin engine's handling of the hand-rolled stream): "Check Status", an
     * on-screen poll, and the on-entry check all use this so an approved registrant always learns
     * it. Never throws — any transport/parse failure resolves to [StreamedRegistrationStatus.Pending]
     * so the screen safely stays in its waiting state.
     *
     * @param userId The user ID to check
     * @return the current status; [StreamedRegistrationStatus.Pending] on any failure
     */
    suspend fun fetchStatus(userId: String): StreamedRegistrationStatus
}
