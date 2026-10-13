package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookStore
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.playback.PlayerEngine
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * [LoadedBookRepository] over a device-local [LoadedBookStore]. The persisted id is read once, lazily, under
 * a lock; until then [observe] says [LoadedBook.Restoring]. A loaded book whose Room row is gone reads as
 * [LoadedBook.NothingLoaded] (the session clears the key when it sees the same thing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LoadedBookRepositoryImpl(
    private val store: LoadedBookStore,
    private val bookRepository: BookRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val engine: PlayerEngine,
) : LoadedBookRepository {
    /** Whether the persisted id has been read from [store] yet, and what it was. */
    private sealed interface Persisted {
        /** Not read yet: [observe] says [LoadedBook.Restoring]. */
        data object Unread : Persisted

        /** Read; [bookId] is null when nothing is loaded. */
        data class Read(
            val bookId: BookId?,
        ) : Persisted
    }

    private val persisted = MutableStateFlow<Persisted>(Persisted.Unread)
    private val readLock = Mutex()

    private suspend fun ensureRead(): BookId? {
        (persisted.value as? Persisted.Read)?.let { return it.bookId }
        return readLock.withLock {
            (persisted.value as? Persisted.Read)?.bookId
                ?: readStoreOrNothing().also { persisted.value = Persisted.Read(it) }
        }
    }

    /** A store that cannot be read (a locked keystore) means nothing is loaded, not Restoring forever. */
    private suspend fun readStoreOrNothing(): BookId? =
        try {
            store.readLoadedBookId()?.let(::BookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Could not read the loaded book; starting with nothing loaded" }
            null
        }

    override suspend fun loadedBookId(): BookId? = ensureRead()

    override fun observeLoadedBookId(): Flow<BookId?> =
        persisted
            .onStart { ensureRead() }
            .filterIsInstance<Persisted.Read>()
            .map { it.bookId }
            .distinctUntilChanged()

    override suspend fun setLoaded(bookId: BookId) {
        if (ensureRead() == bookId) return
        store.saveLoadedBookId(bookId.value)
        persisted.value = Persisted.Read(bookId)
    }

    override suspend fun clear() {
        ensureRead()
        store.saveLoadedBookId(null)
        persisted.value = Persisted.Read(null)
    }

    override fun observe(): Flow<LoadedBook> =
        flow {
            // Restoring is said before the first read, however fast it is, so Home never mistakes a not-yet-read
            // id for "nothing loaded".
            if (persisted.value == Persisted.Unread) emit(LoadedBook.Restoring)
            ensureRead()
            emitAll(
                persisted.flatMapLatest { state ->
                    when (state) {
                        Persisted.Unread -> flowOf(LoadedBook.Restoring)
                        is Persisted.Read -> state.bookId?.let(::observeLoaded) ?: flowOf(LoadedBook.NothingLoaded)
                    }
                },
            )
        }.distinctUntilChanged()

    private fun observeLoaded(bookId: BookId): Flow<LoadedBook> =
        combine(
            flow = bookRepository.observeBookDetail(bookId.value).map { it != null }.distinctUntilChanged(),
            flow2 = engine.loadedBookId,
            flow3 = engine.phase,
            flow4 = positionRepository.observe(bookId).map { it?.isFinished == true }.distinctUntilChanged(),
        ) { rowExists, engineBook, phase, isFinished ->
            if (!rowExists) {
                LoadedBook.NothingLoaded
            } else {
                LoadedBook.Loaded(
                    bookId = bookId.value,
                    isPlaying = engineBook == bookId && phase == EnginePhase.PLAYING,
                    isFinished = isFinished,
                )
            }
        }
}
