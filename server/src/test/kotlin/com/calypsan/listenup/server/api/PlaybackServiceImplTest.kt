@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class PlaybackServiceImplTest :
    FunSpec({

        fun buildDeps(db: org.jetbrains.exposed.v1.jdbc.Database): Triple<BookRepository, PlaybackPositionRepository, AudioUrlSigner> {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val bookRepo = BookRepository(
                db = db,
                bus = bus,
                registry = registry,
                libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/fake/library")),
                contributorRepository = ContributorRepository(db, bus, registry),
                seriesRepository = SeriesRepository(db, bus, registry),
            )
            val positionRepo = PlaybackPositionRepository(db = db, bus = bus, registry = SyncRegistry())
            val signer = AudioUrlSigner(AudioUrlSigner.deriveSigningKey("x".repeat(32)))
            return Triple(bookRepo, positionRepo, signer)
        }

        fun principal(userId: String = "u1"): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(
                    userId = UserId(userId),
                    sessionId = SessionId("session-$userId"),
                    role = UserRole.MEMBER,
                )
            }

        test("prepare returns PreparedPlayback with audio files ordered by index for an unplayed book") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    val result = service.prepare(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    val pb = success.data

                    pb.bookId shouldBe "b1"
                    pb.audioFiles shouldHaveSize 3
                    pb.audioFiles[0].index shouldBe 0
                    pb.audioFiles[0].fileId shouldBe "af-0"
                    pb.audioFiles[0].format shouldBe "m4b"
                    pb.audioFiles[0].durationMs shouldBe 1_000_000L
                    pb.audioFiles[0].sizeBytes shouldBe 100_000_000L
                    pb.audioFiles[1].index shouldBe 1
                    pb.audioFiles[1].fileId shouldBe "af-1"
                    pb.audioFiles[2].index shouldBe 2
                    pb.audioFiles[2].fileId shouldBe "af-2"
                    pb.resumePosition.shouldBeNull()
                }
            }
        }

        test("prepare returns the caller's resume position when one exists") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))
                    positionRepo.recordPosition(
                        userId = "u1",
                        bookId = "b1",
                        positionMs = 42_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.25f,
                        currentChapterId = "chap-1",
                    )

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    val result = service.prepare(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    val pos = success.data.resumePosition.shouldNotBeNull()
                    pos.bookId shouldBe "b1"
                    pos.positionMs shouldBe 42_000L
                    pos.playbackSpeed shouldBe 1.25f
                }
            }
        }

        test("prepare returns a signed URL for each audio file that the signer verifies") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    val result = service.prepare(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    val file = success.data.audioFiles[0]

                    // URL shape: /api/v1/audio/{bookId}/{fileId}?u=...&exp=...&sig=...
                    file.url.shouldNotBeNull()
                    val queryString = file.url.substringAfter("?")
                    val params = queryString.split("&").associate {
                        it.substringBefore("=") to it.substringAfter("=")
                    }
                    val userId = params["u"].shouldNotBeNull()
                    val exp = params["exp"]?.toLong().shouldNotBeNull()
                    val sig = params["sig"].shouldNotBeNull()
                    signer.verify(userId, "b1", file.fileId, exp, sig) shouldBe true
                }
            }
        }

        test("getPosition returns null for a book the user has never played") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    val result = service.getPosition(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PlaybackPositionSyncPayload?>>()
                    success.data.shouldBeNull()
                }
            }
        }

        test("getPosition returns the stored position after recordPosition") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    service.recordPosition(RecordPositionRequest(
                        bookId = "b1",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.5f,
                        currentChapterId = "chap-x",
                    ))

                    val result = service.getPosition(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PlaybackPositionSyncPayload?>>()
                    val pos = success.data.shouldNotBeNull()
                    pos.positionMs shouldBe 99_000L
                    pos.playbackSpeed shouldBe 1.5f
                    pos.currentChapterId shouldBe "chap-x"
                }
            }
        }

        test("recordPosition stores the position using the principal's userId, not any request field") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))

                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    service.recordPosition(RecordPositionRequest(
                        bookId = "b1",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    ))

                    // u1 sees the position
                    val u1pos = positionRepo.getPosition("u1", "b1").shouldNotBeNull()
                    u1pos.positionMs shouldBe 5_000L

                    // u2 sees no position for the same book
                    positionRepo.getPosition("u2", "b1").shouldBeNull()
                }
            }
        }

        test("per-user isolation: two users' prepare calls return independent positions") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    bookRepo.upsert(bookWithThreeFiles("b1"))
                    positionRepo.recordPosition("u1", "b1", 10_000L, 1_730_000_000_000L, false, 1.0f, null)
                    positionRepo.recordPosition("u2", "b1", 20_000L, 1_730_000_000_000L, false, 1.5f, "chap-2")

                    val svc1 = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )
                    val svc2 = svc1.copyWith(principal("u2"))

                    val r1 = svc1.prepare(BookId("b1")).shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    val r2 = svc2.prepare(BookId("b1")).shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()

                    r1.data.resumePosition?.positionMs shouldBe 10_000L
                    r2.data.resumePosition?.positionMs shouldBe 20_000L
                }
            }
        }

        test("prepare returns SyncError.NotFound for an unknown bookId") {
            withInMemoryDatabase {
                val (bookRepo, positionRepo, signer) = buildDeps(this)
                runTest {
                    val service = PlaybackServiceImpl(
                        bookRepository = bookRepo,
                        audioFileLocator = AudioFileLocator(this@withInMemoryDatabase),
                        audioUrlSigner = signer,
                        playbackPositionRepository = positionRepo,
                        principal = principal("u1"),
                    )

                    val result = service.prepare(BookId("nonexistent"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })

private fun bookWithThreeFiles(bookId: String): BookSyncPayload =
    BookSyncPayload(
        id = bookId,
        title = "Test Book",
        sortTitle = "Test Book",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_000_000L,
        cover = null,
        rootRelPath = "Author/Test Book",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(id = "af-0", index = 0, filename = "01.m4b", format = "m4b", codec = "aac", duration = 1_000_000L, size = 100_000_000L),
                BookAudioFilePayload(id = "af-1", index = 1, filename = "02.m4b", format = "m4b", codec = "aac", duration = 1_000_000L, size = 100_000_000L),
                BookAudioFilePayload(id = "af-2", index = 2, filename = "03.m4b", format = "m4b", codec = "aac", duration = 1_000_000L, size = 100_000_000L),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "chap-1", title = "Chapter 1", duration = 3_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
