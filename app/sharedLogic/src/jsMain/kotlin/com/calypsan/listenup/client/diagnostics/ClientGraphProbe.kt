package com.calypsan.listenup.client.diagnostics

import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.buildConfigured
import com.calypsan.listenup.client.data.repository.BookIngestPort
import com.calypsan.listenup.client.di.sharedModules
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.w3c.dom.Worker

/**
 * What a client-graph self-check observed. Plain values, same reasoning as [BrowserStoreProbe]:
 * the Koin graph and the Room layer stay `internal` to this module, and the result crosses to a
 * browser test or a diagnostics surface without exposing either.
 */
data class ClientGraphProbe(
    /** The full shared Koin graph started and `BookRepository` resolved through it. */
    val repositoryResolved: Boolean,
    /** Title read back through the public repository for the probe book, or null. */
    val ingestedTitle: String?,
)

/**
 * Boots the real shared Koin graph — the same module list every native client starts — inside an
 * isolated `koinApplication`, resolves the real `BookRepository`, ingests a probe book through
 * the internal sync-side port, and reads it back through the public repository surface.
 *
 * [dbName] overrides the production database name so OPFS state from other runs cannot leak in;
 * OPFS outlives the page and the test harness reuses the browser profile.
 */
suspend fun probeClientGraph(
    worker: Worker,
    dbName: String,
): ClientGraphProbe {
    val app =
        koinApplication {
            allowOverride(true)
            modules(
                sharedModules +
                    module {
                        single<Worker> { worker }
                        single<ListenUpDatabase> {
                            Room
                                .databaseBuilder<ListenUpDatabase>(name = dbName)
                                .buildConfigured(WebWorkerSQLiteDriver(get()))
                        }
                    },
            )
        }

    return try {
        val repository = app.koin.get<BookRepository>()
        val ingested =
            (repository as BookIngestPort).upsertWithAudioFiles(
                book =
                    BookEntity(
                        id = BookId(PROBE_BOOK_ID),
                        libraryId = LibraryId(PROBE_LIBRARY_ID),
                        folderId = FolderId(PROBE_FOLDER_ID),
                        title = PROBE_TITLE,
                        totalDuration = 0L,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                audioFiles = emptyList(),
            )
        if (ingested is AppResult.Failure) {
            return ClientGraphProbe(repositoryResolved = true, ingestedTitle = null)
        }
        ClientGraphProbe(
            repositoryResolved = true,
            ingestedTitle = repository.getBookListItem(PROBE_BOOK_ID)?.title,
        )
    } finally {
        app.close()
    }
}

private const val PROBE_BOOK_ID = "client-graph-probe"
private const val PROBE_LIBRARY_ID = "client-graph-probe-library"
private const val PROBE_FOLDER_ID = "client-graph-probe-folder"
private const val PROBE_TITLE = "Foundation"
