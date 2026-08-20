package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.SyncedPlaybackPreferences
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeUserPreferencesRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Where "the default" comes from when a book carries no per-book override.
 *
 * Regression: the player used to read a device-local copy of the synced playback defaults. After
 * clearing app storage that copy was gone, so a book resumed at 1x while the Settings screen —
 * reading the synced store — correctly showed 2x. There is now one store, and this spec pins the
 * player to it, including when the server is unreachable.
 *
 * The book is modelled as fully downloaded so `prepare()` is skipped entirely and the assertions
 * are about default resolution and nothing else.
 */
class PlaybackDefaultsSourceOfTruthTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()

        afterSpec { db.close() }

        val bookId = BookId("book-defaults-1")
        val audioFileId = "af-defaults-1"

        beforeTest {
            db.audioFileDao().deleteForBook(bookId.value)
            db.bookDao().deleteById(bookId)
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Defaults Test Book",
                    sortTitle = "Defaults Test Book",
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 1_000L,
                    description = null,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    abridged = false,
                    normalizationGainDb = null,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
            db.audioFileDao().upsertAll(
                listOf(
                    AudioFileEntity(
                        bookId = bookId,
                        index = 0,
                        id = audioFileId,
                        filename = "01.mp3",
                        format = "mp3",
                        codec = "mp3",
                        duration = 1_000L,
                        size = 1_000L,
                    ),
                ),
            )
        }

        /** The book's saved position, or null when it has never been played. */
        fun trackerWith(position: PlaybackPosition?): ProgressTracker {
            val repo: PlaybackPositionRepository = mock()
            everySuspend { repo.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { repo.get(any<BookId>()) } returns AppResult.Success(position)
            return buildProgressTracker(positionRepository = repo)
        }

        // The production PlaybackPreferences binding, over the synced store the Settings screen reads.
        fun buildPreparer(
            synced: FakeUserPreferencesRepository,
            savedPosition: PlaybackPosition?,
        ): PlaybackPreparer {
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)

            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("https://server.test")

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            // Fully downloaded: prepare() is never called, and getPosition() fails the way an
            // unreachable server does — so resolution falls entirely to the local stores.
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(audioFileId) } returns "/local/$audioFileId.mp3"
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)

            val prepareRepository: PlaybackPrepareRepository = mock()
            everySuspend { prepareRepository.getPosition(any()) } returns
                AppResult.Failure(InternalError(debugInfo = "offline"))

            return PlaybackPreparer(
                serverConfig = serverConfig,
                playbackPreferences = SyncedPlaybackPreferences(userPreferences = synced),
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = trackerWith(savedPosition),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = prepareRepository,
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
                nowMillis = { 1_000_000_000_000L },
            )
        }

        fun savedPosition(
            playbackSpeed: Float = 1.0f,
            hasCustomSpeed: Boolean = false,
            volumeBoostDb: Float = 0f,
            hasCustomBoost: Boolean = false,
        ): PlaybackPosition =
            PlaybackPosition(
                bookId = bookId.value,
                positionMs = 500L,
                playbackSpeed = playbackSpeed,
                hasCustomSpeed = hasCustomSpeed,
                volumeBoostDb = volumeBoostDb,
                hasCustomBoost = hasCustomBoost,
                measuredGainDb = null,
                updatedAtMs = 1_000L,
                syncedAtMs = null,
                lastPlayedAtMs = 1_000L,
                isFinished = false,
            )

        val syncedTwoX =
            FakeUserPreferencesRepository.DEFAULTS.copy(
                defaultPlaybackSpeed = 2.0f,
                defaultVolumeBoostDb = 6.0f,
            )

        test("a book with no custom speed resumes at the synced default, not the stock 1x") {
            runTest {
                val result = buildPreparer(FakeUserPreferencesRepository(syncedTwoX), savedPosition()).prepare(bookId)

                result.shouldNotBeNull()
                result.resumeSpeed shouldBe 2.0f
                result.resumeBoostDb shouldBe 6.0f
            }
        }

        test("a never-played book resumes at the synced default too") {
            runTest {
                val result = buildPreparer(FakeUserPreferencesRepository(syncedTwoX), savedPosition = null).prepare(bookId)

                result.shouldNotBeNull()
                result.resumeSpeed shouldBe 2.0f
                result.resumeBoostDb shouldBe 6.0f
            }
        }

        test("a book's own custom speed and boost still win over the synced defaults") {
            runTest {
                val custom =
                    savedPosition(
                        playbackSpeed = 1.25f,
                        hasCustomSpeed = true,
                        volumeBoostDb = 3.0f,
                        hasCustomBoost = true,
                    )

                val result = buildPreparer(FakeUserPreferencesRepository(syncedTwoX), custom).prepare(bookId)

                result.shouldNotBeNull()
                result.resumeSpeed shouldBe 1.25f
                result.resumeBoostDb shouldBe 3.0f
            }
        }

        test("an unreachable server never demotes the synced defaults") {
            runTest {
                val synced = FakeUserPreferencesRepository(syncedTwoX)
                synced.failGetPreferences = InternalError(debugInfo = "server unreachable")

                val result = buildPreparer(synced, savedPosition()).prepare(bookId)

                result.shouldNotBeNull()
                result.resumeSpeed shouldBe 2.0f
                result.resumeBoostDb shouldBe 6.0f
                // Resolution read the local cache only — it never reached for the network.
                synced.getPreferencesCalls shouldBe 0
            }
        }
    })
