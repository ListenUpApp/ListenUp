package com.calypsan.listenup.server.testing

import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry

/**
 * Seeds a series named [name] containing [bookIds] (each seeded as a book). The library and folder must
 * already exist ([seedTestLibraryAndFolder]).
 */
internal suspend fun SqlTestDatabases.seedSeriesWithBooks(
    name: String,
    vararg bookIds: String,
): SeriesId {
    val series = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry()).resolveOrCreate(name)
    bookIds.forEachIndexed { index, bookId ->
        sql.seedTestBook(bookId)
        sql.bookSeriesMembershipsQueries.insertIfAbsent(
            book_id = bookId,
            series_id = series.value,
            sequence = (index + 1).toDouble(),
            ordinal = 0L,
        )
    }
    return series
}
