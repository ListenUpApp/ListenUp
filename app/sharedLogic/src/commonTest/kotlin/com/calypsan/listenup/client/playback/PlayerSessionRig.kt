package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.data.repository.LoadedBookRepositoryImpl
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.fake.FakeBookAvailability
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import com.calypsan.listenup.client.test.fake.FakeLoadedBookStore
import com.calypsan.listenup.client.test.fake.FakeNetworkMonitor
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPreferences
import com.calypsan.listenup.client.test.fake.FakePlayerEngine
import com.calypsan.listenup.client.test.fake.FakeServerReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Three 100 s chapters in a 300 s book. */
internal val threeChapters =
    listOf(
        Chapter(id = "c1", title = "One", duration = 100_000, startTime = 0),
        Chapter(id = "c2", title = "Two", duration = 100_000, startTime = 100_000),
        Chapter(id = "c3", title = "Three", duration = 100_000, startTime = 200_000),
    )

/** A [PlayerSession] over fakes, with its own cancellable scope on the test scheduler. */
internal class PlayerSessionRig(
    testScope: TestScope,
    storedBookId: String? = null,
    offlineCopiesSupported: Boolean = true,
) {
    val scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler))
    val engine = FakePlayerEngine()
    val store = FakeLoadedBookStore(storedBookId)
    val books = FakeBookRepository()
    val positions = FakePlaybackPositionRepository()
    val downloads = FakeDownloadRepository()
    val inbox = FakeInboxRepository()
    val network = FakeNetworkMonitor()
    val reachability = FakeServerReachability()
    val availability = FakeBookAvailability()
    val auth = FakeAuthSession()
    val preferences = FakePlaybackPreferences()
    val sleep = SleepTimerManager(scope, nowMillis = { 0L }, elapsedMillis = { testScope.testScheduler.currentTime })
    val loadedBooks = LoadedBookRepositoryImpl(store, books, positions, engine)
    val session =
        PlayerSession(
            engine = engine,
            loadedBooks = loadedBooks,
            bookRepository = books,
            downloadRepository = downloads,
            positionRepository = positions,
            availability = availability,
            inboxRepository = inbox,
            networkMonitor = network,
            serverReachability = reachability,
            authSession = auth,
            sleepTimer = sleep,
            playbackPreferences = preferences,
            offlineCopiesSupported = offlineCopiesSupported,
            scope = scope,
        )

    /** Put [id] in the local mirror: a live row, its detail and its chapters. The engine prepares the same. */
    fun withBook(
        id: String,
        chapters: List<Chapter> = threeChapters,
        durationMs: Long = 300_000,
    ) {
        books.setBooks(books.currentBooks() + TestData.bookListItem(id = id, duration = durationMs))
        books.setBookDetail(TestData.bookDetail(id = id, duration = durationMs))
        books.setChapters(id, chapters)
        engine.preparedChapters = chapters
        engine.preparedDurationMs = durationMs
    }

    val loaded: PlayerSessionState.Loaded get() = session.state.value as PlayerSessionState.Loaded

    fun close() = scope.cancel()
}
