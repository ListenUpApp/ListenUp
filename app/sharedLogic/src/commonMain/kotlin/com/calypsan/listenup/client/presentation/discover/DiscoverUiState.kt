package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.ShelfCover

/**
 * UI state for the Discover shelves section.
 */
sealed interface DiscoverShelvesUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : DiscoverShelvesUiState

    /** Shelves grouped by owner, loaded via RPC. */
    data class Ready(
        val users: List<DiscoverUserShelves>,
    ) : DiscoverShelvesUiState {
        val isEmpty: Boolean
            get() = users.isEmpty()

        val totalShelfCount: Int
            get() = users.sumOf { it.shelves.size }
    }

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : DiscoverShelvesUiState {
        /** True when the failure is connectivity (offline, timed out): the section says it needs a connection. */
        val needsConnection: Boolean
            get() = error.isConnectivityFailure()
    }
}

/**
 * User with their shelves for Discover screen.
 */
data class DiscoverUserShelves(
    val user: DiscoverShelfOwner,
    val shelves: List<DiscoverShelfUi>,
)

/**
 * Shelf owner info for display.
 *
 * Avatar color is derived from [id] at the UI layer — the discovery contract
 * supplies owner identity but no avatar color.
 */
data class DiscoverShelfOwner(
    val id: String,
    val displayName: String,
)

/**
 * One of another reader's public shelves on Discover.
 *
 * @property id The shelf.
 * @property name Its name.
 * @property description Its description, when it has one.
 * @property bookCount How many of its books the reader can access.
 * @property totalDurationSeconds Always 0 for a discovered shelf: its books never enter this device's
 *   mirror, so their lengths are unknown here.
 * @property covers Up to four cover tiles for its 2×2 mosaic, in shelf order; fewer when this device
 *   lacks some of the books.
 */
data class DiscoverShelfUi(
    val id: String,
    val name: String,
    val description: String?,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val covers: List<ShelfCover>,
)

/**
 * UI state for the "What Others Are Listening To" section.
 */
sealed interface CurrentlyListeningUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : CurrentlyListeningUiState

    /**
     * The roster from Room: live listeners first, then each other person's most recent book.
     *
     * @property sessions The rows, in display order.
     * @property observedAtMs When the server last sent this roster (epoch ms); null when it is empty.
     * @property isStale True while the server is unreachable, so the roster may be [observedAtMs] old.
     */
    data class Ready(
        val sessions: List<CurrentlyListeningUiSession>,
        val observedAtMs: Long?,
        val isStale: Boolean,
    ) : CurrentlyListeningUiState {
        /** Whether nobody is shown at all. */
        val isEmpty: Boolean
            get() = sessions.isEmpty()

        /** People listening right now. */
        val liveCount: Int
            get() = sessions.count { it.isLive }

        /** People shown on the book they last played, within the server's recent window. */
        val recentCount: Int
            get() = sessions.count { !it.isLive }
    }

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : CurrentlyListeningUiState {
        /** True when the failure is connectivity (offline, timed out): the section says it needs a connection. */
        val needsConnection: Boolean
            get() = error.isConnectivityFailure()
    }
}

/**
 * UI state for the "Discover Something New" section.
 */
sealed interface DiscoverBooksUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : DiscoverBooksUiState

    /** Random unstarted books from Room. */
    data class Ready(
        val books: List<DiscoverUiBook>,
    ) : DiscoverBooksUiState {
        val isEmpty: Boolean
            get() = books.isEmpty()
    }

    /** Every opener and standalone in the library is started: nothing left to suggest, yet the library is not empty. */
    data object AllStarted : DiscoverBooksUiState

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : DiscoverBooksUiState
}

/**
 * UI state for the "Recently Added" section.
 */
sealed interface RecentlyAddedUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : RecentlyAddedUiState

    /** Newly added books loaded from Room. */
    data class Ready(
        val books: List<RecentlyAddedUiBook>,
    ) : RecentlyAddedUiState {
        val isEmpty: Boolean
            get() = books.isEmpty()
    }

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : RecentlyAddedUiState
}

// === UI Model Types ===

/**
 * One row of "What Others Are Listening To": one other person, on one book.
 *
 * [isLive] says which kind of row it is — someone listening right now, or someone shown on the book
 * they most recently played — and [lastActiveAt] is the single timestamp either kind carries. The
 * card renders "Listening now" for the former and a relative time for the latter.
 *
 * @property sessionId Stable identity for this row
 * @property userId The other user
 * @property bookId The book shown for them
 * @property bookTitle Title, enriched from the local library
 * @property authorName Author, enriched from the local library
 * @property coverPath Local cover file path, when the cover is cached
 * @property coverHash Cover version hash, for cache busting
 * @property displayName Their public display name
 * @property lastActiveAt Epoch-ms they were last active on [bookId]
 * @property isLive True when they are listening right now
 */
data class CurrentlyListeningUiSession(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val bookTitle: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val displayName: String,
    val lastActiveAt: Long,
    val isLive: Boolean,
)

/**
 * A "Something new" card: a book the reader has not started, first in its series or standalone.
 *
 * @property id The book.
 * @property title Its title.
 * @property authorName Its primary author, when known.
 * @property coverPath The local cover file, or null when not downloaded here.
 * @property coverHash The cover's content version.
 * @property seriesName The series it starts; null when standalone (the card reads "Standalone").
 * @property sequenceLabel Its place in [seriesName] ("1"); null when standalone or unnumbered.
 * @property durationMs Its total length.
 */
data class DiscoverUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val seriesName: String?,
    val sequenceLabel: String?,
    val durationMs: Long,
)

/**
 * Book for "Recently Added" with resolved local cover path.
 */
data class RecentlyAddedUiBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverHash: String?,
    val createdAt: Long,
)
