package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ShelfBookFactRow
import com.calypsan.listenup.client.data.local.db.ShelfCoverRow

/** Cover tiles a shelf card draws at most: the 2x2 mosaic. */
private const val SHELF_MOSAIC_TILES = 4

/** What a shelf card shows of its books: the mosaic's cover rows and the shelf's total length. */
internal data class ShelfTileFacts(
    val covers: List<ShelfCoverRow>,
    val totalDurationMs: Long,
) {
    companion object {
        /** A shelf none of whose books are in the local mirror yet. */
        val Empty = ShelfTileFacts(covers = emptyList(), totalDurationMs = 0L)
    }
}

/**
 * The tile facts of one shelf from its live, non-held books present in Room ([rows], any order): the first four with a
 * cover hash by `sortOrder` (the rows `ShelfDao.coverRowsForShelf` returns), and the sum of every row's duration
 * (`ShelfDao.totalDurationMsFor`). Pure.
 */
internal fun shelfTileFactsOf(rows: List<ShelfBookFactRow>): ShelfTileFacts =
    ShelfTileFacts(
        covers =
            rows
                .asSequence()
                .sortedBy { it.sortOrder }
                .filter { it.coverHash != null }
                .take(SHELF_MOSAIC_TILES)
                .map { row ->
                    ShelfCoverRow(
                        bookId = row.bookId,
                        coverHash = row.coverHash,
                        coverDownloadedAt = row.coverDownloadedAt,
                    )
                }.toList(),
        totalDurationMs = rows.sumOf { it.totalDuration },
    )
