package com.calypsan.listenup.client.presentation.library

/**
 * Which reading state the Library's Books view shows.
 *
 * Session-scoped by design (spec §3.1.1): it lives on the ViewModel's intent and is never persisted,
 * so a cold start always opens on [ALL] and a forgotten "Finished" filter can never hide the library.
 * A single enum property is safe across Swift Export; never expose a collection of these.
 */
enum class BookStatusFilter {
    ALL,
    IN_PROGRESS,
    NOT_STARTED,
    FINISHED,
}
