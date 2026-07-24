package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.BookEvent
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository for real-time event streams.
 *
 * Provides filtered flows of domain events for presentation layer consumption.
 * This abstracts the underlying event-stream transport, ensuring ViewModels
 * don't depend on data layer types.
 *
 * Implementation maps server-push events to domain events and provides typed flows
 * for each event category that ViewModels need.
 */
interface EventStreamRepository {
    /**
     * Flow of admin-related events.
     *
     * Used by AdminViewModel and AdminInboxViewModel for real-time updates
     * when users register, are approved, or books are added/released from inbox.
     */
    val adminEvents: Flow<AdminEvent>

    /**
     * Flow of book-related events.
     *
     * Used by BookReadersViewModel for real-time updates when reading
     * sessions are created or updated.
     */
    val bookEvents: Flow<BookEvent>
}
