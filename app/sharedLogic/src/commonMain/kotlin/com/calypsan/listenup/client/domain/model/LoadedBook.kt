package com.calypsan.listenup.client.domain.model

/**
 * What a non-player screen needs to know about this device's loaded book (spec §4.9; Home P-R1–P-R6).
 * Emits only when one of its fields changes, never on a position tick.
 */
sealed interface LoadedBook {
    /** Launch, before the persisted id has been read. Resolves from local storage, never the network. */
    data object Restoring : LoadedBook

    /** Nothing is loaded: no mini player. */
    data object NothingLoaded : LoadedBook

    /**
     * A book is loaded (§3.4).
     *
     * @property isPlaying the engine holds this book and its audio is advancing.
     * @property isFinished the book's position row says it is finished. Finishing does not unload.
     */
    data class Loaded(
        val bookId: String,
        val isPlaying: Boolean,
        val isFinished: Boolean,
    ) : LoadedBook
}
