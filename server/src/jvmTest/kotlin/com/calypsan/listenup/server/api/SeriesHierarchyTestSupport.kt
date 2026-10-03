package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.rootPrincipal
import io.kotest.matchers.types.shouldBeInstanceOf

internal data class HierarchyDeps(
    val service: SeriesServiceImpl,
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val bus: ChangeBus,
)

internal fun makeHierarchyDeps(dbs: SqlTestDatabases): HierarchyDeps {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val seriesRepo = SeriesRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(db = dbs.sql, bus = bus, registry = registry),
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = dbs.sql, bus = bus, registry = registry),
        )
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            principal = rootPrincipal(),
        )
    return HierarchyDeps(service, seriesRepo, bookRepo, bus)
}

/** The live payload of [id]; fails the test when the series is missing. */
internal suspend fun HierarchyDeps.series(id: SeriesId): SeriesSyncPayload = checkNotNull(seriesRepo.findById(id.value))

/** Writes [parent] + [position] straight through the substrate, bypassing the service. */
internal suspend fun HierarchyDeps.place(
    id: SeriesId,
    parent: SeriesId?,
    position: Int?,
) {
    seriesRepo
        .upsert(series(id).copy(parentId = parent?.value, parentPosition = position))
        .shouldBeInstanceOf<AppResult.Success<*>>()
}

/** Minimal [BookSyncPayload] belonging to one series. */
internal fun bookInSeries(
    id: String,
    seriesId: SeriesId,
    sequence: Double = 1.0,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = id,
        sortTitle = id,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        hasScanWarning = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = listOf(BookSeriesPayload(id = seriesId.value, name = "placeholder", sequence = sequence)),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters = listOf(BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L)),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
