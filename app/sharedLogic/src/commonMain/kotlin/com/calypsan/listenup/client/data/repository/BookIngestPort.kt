package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity

/**
 * Data-layer seam for atomically ingesting a book aggregate from the network. `internal` to
 * `:app:sharedLogic`: its implementation ([BookRepositoryImpl]) and the only binding ([BookModule])
 * both live in `:app:sharedLogic`, so it never needs to be seen from a sibling module.
 *
 * Lives in the data layer, not the domain layer, because it speaks Room entity types —
 * callers (playback fallback fetch, sync handlers) already hold entities built from
 * API responses and routing through a domain→entity round-trip would be wasteful.
 *
 * Implemented by [BookRepositoryImpl]; consumed by [PlaybackPreparer] and similar
 * data-layer ingest paths. Not part of the [com.calypsan.listenup.client.domain.repository.BookRepository]
 * contract so the domain interface stays free of Room types.
 */
internal interface BookIngestPort {
    /**
     * Atomically upsert a book row and replace its audio-file rows.
     *
     * Deletes existing audio-file rows for the book and inserts the new
     * set inside a single transaction, so the DB never holds a book with
     * a partially-replaced audio-file list.
     *
     * @param book The book entity to upsert.
     * @param audioFiles The complete set of audio-file entities to store.
     *   Pass an empty list to clear all audio files without inserting new ones.
     */
    suspend fun upsertWithAudioFiles(
        book: BookEntity,
        audioFiles: List<AudioFileEntity>,
    ): AppResult<Unit>
}
