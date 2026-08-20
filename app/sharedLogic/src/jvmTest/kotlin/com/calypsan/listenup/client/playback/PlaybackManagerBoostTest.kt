package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackBandwidthCoordinator
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * PR3 T3 regression tests for [PlaybackManager]'s boost/gain state — mirrors
 * [PlaybackManagerSpeedTest]'s harness for the speed surface.
 *
 * Pins the iOS-parity gain semantics:
 * 1. `prepareForPlayback` carries [PlaybackPreparer]'s resolved boost/measured/tag inputs
 *    onto [PlaybackManager.PrepareResult] and seeds [PlaybackManager.effectiveGainDb] via
 *    [com.calypsan.listenup.client.playback.loudness.VolumeGain.effectiveGainDb] (measured wins
 *    over the file tag, falling back to 0 when neither exists).
 * 2. [PlaybackManager.onVolumeBoostChanged] / [PlaybackManager.onBoostReset] recompute
 *    [PlaybackManager.effectiveGainDb] immediately and persist through
 *    [PlaybackProgressReporter] → [ProgressTracker] → the position repository — never a
 *    live jump from a new measurement arriving mid-book (the manager never sees those).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerBoostTest :
    FunSpec({
        fun defaultPlaybackPreferences(): PlaybackPreferences {
            val prefs: PlaybackPreferences = mock()
            everySuspend { prefs.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { prefs.getDefaultVolumeBoostDb() } returns 0.0f
            return prefs
        }

        fun createPlaybackManager(
            db: ListenUpDatabase,
            playbackPreferences: PlaybackPreferences = defaultPlaybackPreferences(),
            progressTracker: ProgressTracker = buildProgressTracker(scope = CoroutineScope(Job())),
            scope: CoroutineScope = CoroutineScope(Job()),
        ): PlaybackManager {
            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("https://example.test")

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)
            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = playbackPreferences,
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = progressTracker,
                reporter =
                    PlaybackProgressReporter(
                        progressTracker,
                        recorder = null,
                        scope = scope,
                        localPreferences = localPreferences,
                    ),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository("af-0"),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
            )
        }

        // Creates a [PlaybackManager] whose internal [CoroutineScope] is backed by the
        // [TestScope] from [runTest]. Use this for tests that need [advanceUntilIdle]
        // to drain coroutines launched inside [PlaybackManager] or [ProgressTracker].
        //
        // Returns both the manager and the [PlaybackPositionRepository] mock so tests
        // can assert on it.
        fun TestScope.createPlaybackManagerWithScope(
            db: ListenUpDatabase,
            playbackPreferences: PlaybackPreferences = defaultPlaybackPreferences(),
            positionRepository: PlaybackPositionRepository = defaultPositionRepository(),
        ): Pair<PlaybackManager, PlaybackPositionRepository> {
            val progressTrackerScope = CoroutineScope(coroutineContext)
            val managerScope = CoroutineScope(coroutineContext)

            val progressTracker =
                buildProgressTracker(
                    scope = progressTrackerScope,
                    positionRepository = positionRepository,
                )

            val manager =
                createPlaybackManager(
                    db = db,
                    playbackPreferences = playbackPreferences,
                    progressTracker = progressTracker,
                    scope = managerScope,
                )

            return manager to positionRepository
        }

        suspend fun seedBookAndAudioFiles(
            db: ListenUpDatabase,
            normalizationGainDb: Float? = null,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId("book-1"),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = "Test Book",
                    sortTitle = "Test Book",
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 1_800_000L,
                    description = null,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    abridged = false,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                    normalizationGainDb = normalizationGainDb,
                ),
            )
            db.audioFileDao().upsertAll(
                listOf(
                    AudioFileEntity(
                        bookId = BookId("book-1"),
                        index = 0,
                        id = "af-0",
                        filename = "chapter1.m4b",
                        format = "m4b",
                        codec = "aac",
                        duration = 1_800_000L,
                        size = 45_000_000L,
                    ),
                ),
            )
        }

        /** A [PlaybackPositionRepository] whose saved row carries the given gain inputs. */
        fun positionRepositoryWith(
            measuredGainDb: Float?,
            hasCustomBoost: Boolean,
            volumeBoostDb: Float,
        ): PlaybackPositionRepository {
            val repo: PlaybackPositionRepository = mock()
            everySuspend { repo.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { repo.get(any<BookId>()) } returns
                AppResult.Success(
                    PlaybackPosition(
                        bookId = "book-1",
                        positionMs = 0L,
                        playbackSpeed = 1.0f,
                        hasCustomSpeed = false,
                        volumeBoostDb = volumeBoostDb,
                        hasCustomBoost = hasCustomBoost,
                        measuredGainDb = measuredGainDb,
                        updatedAtMs = 1L,
                        syncedAtMs = 1L,
                        lastPlayedAtMs = 1L,
                    ),
                )
            return repo
        }

        // -------------------------------------------------------------------------
        // Prepare — gain inputs flow onto PrepareResult and seed effectiveGainDb
        // -------------------------------------------------------------------------

        test("prepare carries the gain inputs onto PrepareResult") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)
                    val positionRepository =
                        positionRepositoryWith(measuredGainDb = -2f, hasCustomBoost = true, volumeBoostDb = 6f)

                    val (manager, _) =
                        createPlaybackManagerWithScope(db = db, positionRepository = positionRepository)

                    val result = manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()

                    result.resumeBoostDb shouldBeExactly 6f
                    result.measuredGainDb.shouldNotBeNull() shouldBeExactly -2f
                    result.normalizationGainDb.shouldNotBeNull() shouldBeExactly -3f
                }
            } finally {
                db.close()
            }
        }

        test("prepare seeds effectiveGainDb with measured winning over tag") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)
                    val positionRepository =
                        positionRepositoryWith(measuredGainDb = -2f, hasCustomBoost = true, volumeBoostDb = 6f)

                    val (manager, _) =
                        createPlaybackManagerWithScope(db = db, positionRepository = positionRepository)

                    manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()

                    manager.effectiveGainDb.value shouldBeExactly 4f
                }
            } finally {
                db.close()
            }
        }

        test("prepare falls back to the tag when unmeasured") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)

                    // Default position repository returns no saved row (measured = null) and the
                    // default playback preferences resolve boost to 0f (no custom boost saved).
                    val (manager, _) = createPlaybackManagerWithScope(db = db)

                    manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()

                    manager.effectiveGainDb.value shouldBeExactly -3f
                }
            } finally {
                db.close()
            }
        }

        // -------------------------------------------------------------------------
        // onVolumeBoostChanged / onBoostReset — recompute live + persist
        // -------------------------------------------------------------------------

        test("onVolumeBoostChanged recomputes gain and reports") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)
                    val positionRepository =
                        positionRepositoryWith(measuredGainDb = -2f, hasCustomBoost = true, volumeBoostDb = 6f)

                    val (manager, repo) =
                        createPlaybackManagerWithScope(db = db, positionRepository = positionRepository)

                    manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()
                    manager.activateBook(BookId("book-1"))

                    manager.onVolumeBoostChanged(9f)
                    advanceUntilIdle()

                    manager.effectiveGainDb.value shouldBeExactly 7f

                    verifySuspend(VerifyMode.exactly(1)) {
                        repo.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "VolumeBoost(boostDb=9.0, custom=true)" }) {
                                it is PlaybackUpdate.VolumeBoost && it.boostDb == 9f && it.custom
                            },
                        )
                    }
                }
            } finally {
                db.close()
            }
        }

        test("onBoostReset recomputes with the default and reports") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)
                    val positionRepository =
                        positionRepositoryWith(measuredGainDb = -2f, hasCustomBoost = true, volumeBoostDb = 6f)

                    val (manager, repo) =
                        createPlaybackManagerWithScope(db = db, positionRepository = positionRepository)

                    manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()
                    manager.activateBook(BookId("book-1"))

                    manager.onBoostReset(3f)
                    advanceUntilIdle()

                    manager.effectiveGainDb.value shouldBeExactly 1f

                    verifySuspend(VerifyMode.exactly(1)) {
                        repo.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "BoostReset(defaultBoostDb=3.0)" }) {
                                it is PlaybackUpdate.BoostReset && it.defaultBoostDb == 3f
                            },
                        )
                    }
                }
            } finally {
                db.close()
            }
        }

        test("volumeBoostDb state tracks the user's boost") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db, normalizationGainDb = -3f)
                    val positionRepository =
                        positionRepositoryWith(measuredGainDb = -2f, hasCustomBoost = true, volumeBoostDb = 6f)

                    val (manager, _) =
                        createPlaybackManagerWithScope(db = db, positionRepository = positionRepository)

                    manager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()
                    manager.volumeBoostDb.value shouldBeExactly 6f

                    manager.activateBook(BookId("book-1"))
                    manager.onVolumeBoostChanged(9f)

                    manager.volumeBoostDb.value shouldBeExactly 9f
                }
            } finally {
                db.close()
            }
        }
    })
