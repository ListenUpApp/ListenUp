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
}
