package com.calypsan.listenup.client.diagnostics

import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.buildConfigured
import com.calypsan.listenup.client.data.repository.BookIngestPort
import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds

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
    val app = browserGraph(worker, dbName)

    return try {
        val repository = app.koin.get<BookRepository>()
        if (repository.ingestProbeBook() is AppResult.Failure) {
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

/**
 * What a presentation-layer self-check observed. Same reasoning as [ClientGraphProbe]: the Koin
 * graph stays `internal`, and a browser test reads plain values out of it.
 */
data class BookDetailPresentationProbe(
    /** The real [BookDetailViewModel] resolved through the browser graph. */
    val viewModelResolved: Boolean,
    /** Title observed on the ViewModel's `Ready` state, or null if it never got there. */
    val readyTitle: String?,
)

/**
 * Drives the **real** shared [BookDetailViewModel] in a browser: boots the graph, ingests a probe
 * book, resolves the ViewModel through its full twelve-dependency constructor, calls `loadBook`,
 * and waits for the state machine to reach `Ready`.
 *
 * This is the presentation-layer counterpart to [probeClientGraph], and it exists because two
 * things are unproven on this platform until something runs them: whether every one of that
 * ViewModel's dependencies resolves under the browser bindings, and whether `viewModelScope` —
 * `Dispatchers.Main.immediate`, which each of its `stateIn` flows touches at construction — is
 * usable on Kotlin/JS at all.
 *
 * Waits rather than hangs: a state machine that never reaches `Ready` reports a null title, so a
 * failure reads as a failed assertion instead of a truncated suite.
 */
suspend fun probeBookDetailPresentation(
    worker: Worker,
    dbName: String,
): BookDetailPresentationProbe {
    val app = browserGraph(worker, dbName)

    return try {
        app.koin.get<BookRepository>().ingestProbeBook()

        val viewModel = app.koin.get<BookDetailViewModel>()
        viewModel.loadBook(PROBE_BOOK_ID)
        val ready =
            withTimeoutOrNull(READY_TIMEOUT) {
                viewModel.state.filterIsInstance<BookDetailUiState.Ready>().first()
            }

        BookDetailPresentationProbe(
            viewModelResolved = true,
            readyTitle = ready?.book?.title,
        )
    } finally {
        app.close()
    }
}

/**
 * The shared Koin graph as a browser boots it, in an isolated application so probes cannot
 * collide with each other or with a running page.
 *
 * [dbName] overrides the production database name so OPFS state from other runs cannot leak in;
 * OPFS outlives the page and the test harness reuses the browser profile.
 */
private fun browserGraph(
    worker: Worker,
    dbName: String,
) = koinApplication {
    allowOverride(true)
    modules(
        jsSharedModules() +
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

/** Writes the probe book through the sync-side ingest port, the way a sync would. */
private suspend fun BookRepository.ingestProbeBook(): AppResult<*> =
    (this as BookIngestPort).upsertWithAudioFiles(
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

private val READY_TIMEOUT = 10.seconds

private const val PROBE_BOOK_ID = "client-graph-probe"
private const val PROBE_LIBRARY_ID = "client-graph-probe-library"
private const val PROBE_FOLDER_ID = "client-graph-probe-folder"
private const val PROBE_TITLE = "Foundation"
