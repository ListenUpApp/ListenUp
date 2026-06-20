@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import com.calypsan.listenup.server.testing.asSqlDatabase

class PlaybackServiceImplTest :
    FunSpec({

        data class TestDeps(
            val bookRepo: BookRepository,
            val positionRepo: PlaybackPositionRepository,
            val signer: AudioUrlSigner,
            val eventRepo: ListeningEventRepository,
            val statsRepo: UserStatsRepository,
            val accessPolicy: BookAccessPolicy,
            val collectionRepo: CollectionRepository,
            val collectionBookRepo: CollectionBookRepository,
            val grantRepo: CollectionGrantRepository,
        )

        fun buildDeps(db: org.jetbrains.exposed.v1.jdbc.Database): TestDeps {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val bookRepo =
                BookRepository(
                    db = db.asSqlDatabase(),
                    exposedDb = db,
                    bus = bus,
                    registry = registry,
                    contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, registry),
                    seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, registry),
                    genreRepository = GenreRepository(db.asSqlDatabase(), bus, registry),
                )
            val positionRepo = PlaybackPositionRepository(db = db.asSqlDatabase(), bus = bus, registry = SyncRegistry())
            val signer = AudioUrlSigner(AudioUrlSigner.deriveSigningKey("x".repeat(32)))
            val statsRepo = UserStatsRepository(db = db.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
            val updater =
                UserStatsUpdater(
                    sql = db.asSqlDatabase(),
                    userStatsRepo = statsRepo,
                    publicProfileMaintainerProvider = { db.noOpPublicProfileMaintainer() },
                )
            val eventRepo =
                ListeningEventRepository(
                    db = db.asSqlDatabase(),
                    bus = ChangeBus(),
                    registry = SyncRegistry(),
                    userStatsUpdater = updater,
                )
            return TestDeps(
                bookRepo = bookRepo,
                positionRepo = positionRepo,
                signer = signer,
                eventRepo = eventRepo,
                statsRepo = statsRepo,
                accessPolicy = BookAccessPolicy(db),
                collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry),
                collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry),
                grantRepo = CollectionGrantRepository(db = db, bus = bus, registry = registry),
            )
        }

        fun principal(
            userId: String = "u1",
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(
                    userId = UserId(userId),
                    sessionId = SessionId("session-$userId"),
                    role = role,
                )
            }

        fun TestDeps.service(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            userId: String = "u1",
            role: UserRole = UserRole.MEMBER,
        ): PlaybackServiceImpl =
            PlaybackServiceImpl(
                bookRepository = bookRepo,
                audioFileLocator = AudioFileLocator(db),
                audioUrlSigner = signer,
                playbackPositionRepository = positionRepo,
                listeningEventRepository = eventRepo,
                userStatsRepository = statsRepo,
                accessPolicy = accessPolicy,
                principal = principal(userId, role),
                db = db,
            )

        /**
         * Makes [bookId] visible to each (already-seeded) member in [userIds] the pure-union way:
         * drops the book into the per-library `ALL_BOOKS` substrate and grants each a live read
         * grant on it — exactly how production exposes a public book. The users must already exist
         * (`seedTestUser`): the grant's `principal_id` is a FK into `users`.
         */
        suspend fun TestDeps.makeReachable(
            bookId: String,
            vararg userIds: String,
        ) {
            collectionRepo.upsert(playbackCollection("all-books", owner = "system"))
            collectionBookRepo.upsert(playbackMembership("all-books", bookId))
            for (uid in userIds) {
                grantRepo.upsert(playbackShare("grant-$bookId-$uid", "all-books", uid))
            }
        }

        test("prepare returns PreparedPlayback with audio files ordered by index for an unplayed book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")

                    val service = deps.service(db, "u1")

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
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")
                    deps.positionRepo.recordPosition(
                        userId = "u1",
                        bookId = "b1",
                        positionMs = 42_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.25f,
                        currentChapterId = "chap-1",
                    )

                    val service = deps.service(db, "u1")

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
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")

                    val service = deps.service(db, "u1")

                    val result = service.prepare(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    val file = success.data.audioFiles[0]

                    // URL shape: /api/v1/audio/{bookId}/{fileId}?u=...&exp=...&sig=...
                    file.url.shouldNotBeNull()
                    val queryString = file.url.substringAfter("?")
                    val params =
                        queryString.split("&").associate {
                            it.substringBefore("=") to it.substringAfter("=")
                        }
                    val userId = params["u"].shouldNotBeNull()
                    val exp = params["exp"]?.toLong().shouldNotBeNull()
                    val sig = params["sig"].shouldNotBeNull()
                    deps.signer.verify(userId, "b1", file.fileId, exp, sig) shouldBe true
                }
            }
        }

        test("getPosition returns null for a book the user has never played") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")

                    val service = deps.service(db, "u1")

                    val result = service.getPosition(BookId("b1"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PlaybackPositionSyncPayload?>>()
                    success.data.shouldBeNull()
                }
            }
        }

        test("getPosition returns the stored position after recordPosition") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")

                    val service = deps.service(db, "u1")

                    service.recordPosition(
                        RecordPositionRequest(
                            bookId = "b1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = false,
                            playbackSpeed = 1.5f,
                            currentChapterId = "chap-x",
                        ),
                    )

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
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")

                    val service = deps.service(db, "u1")

                    service.recordPosition(
                        RecordPositionRequest(
                            bookId = "b1",
                            positionMs = 5_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        ),
                    )

                    // u1 sees the position
                    val u1pos = deps.positionRepo.getPosition("u1", "b1").shouldNotBeNull()
                    u1pos.positionMs shouldBe 5_000L

                    // u2 sees no position for the same book
                    deps.positionRepo.getPosition("u2", "b1").shouldBeNull()
                }
            }
        }

        test("per-user isolation: two users' prepare calls return independent positions") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1", "u2")
                    deps.positionRepo.recordPosition("u1", "b1", 10_000L, 1_730_000_000_000L, false, 1.0f, null)
                    deps.positionRepo.recordPosition("u2", "b1", 20_000L, 1_730_000_000_000L, false, 1.5f, "chap-2")

                    val svc1 = deps.service(db, "u1")
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
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    val service = deps.service(db, "u1")

                    val result = service.prepare(BookId("nonexistent"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        // ─── prepare access gate ──────────────────────────────────────────────────

        test("prepare returns NotFound for a member on a book in a private collection they can't reach") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("private-book"))
                    deps.collectionRepo.upsert(playbackCollection("private-col", owner = "stranger"))
                    deps.collectionBookRepo.upsert(playbackMembership("private-col", "private-book"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val result = service.prepare(BookId("private-book"))
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("prepare returns the playback for a member granted via ALL_BOOKS (the public substrate)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("member")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("public-book"))
                    // ALL_BOOKS membership + the member's grant = visibility under pure union.
                    deps.collectionRepo.upsert(playbackCollection("all-books", owner = "system"))
                    deps.collectionBookRepo.upsert(playbackMembership("all-books", "public-book"))
                    deps.grantRepo.upsert(playbackShare("g1", "all-books", "member"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val result = service.prepare(BookId("public-book"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    success.data.bookId shouldBe "public-book"
                }
            }
        }

        test("admin prepare sees a private/inbox book a member could not") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("inbox-book"))
                    deps.collectionRepo.upsert(playbackCollection("inbox-col", owner = "stranger", isInbox = true))
                    deps.collectionBookRepo.upsert(playbackMembership("inbox-col", "inbox-book"))

                    val service = deps.service(db, userId = "admin", role = UserRole.ADMIN)

                    val result = service.prepare(BookId("inbox-book"))
                    val success = result.shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                    success.data.bookId shouldBe "inbox-book"
                }
            }
        }

        // ─── write/read access gate (recordPosition / recordListeningEvent / getPosition) ──

        test("recordPosition returns NotFound and writes nothing for a member on an unreachable book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("private-book"))
                    deps.collectionRepo.upsert(playbackCollection("private-col", owner = "stranger"))
                    deps.collectionBookRepo.upsert(playbackMembership("private-col", "private-book"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val result =
                        service.recordPosition(
                            RecordPositionRequest(
                                bookId = "private-book",
                                positionMs = 5_000L,
                                lastPlayedAt = 1_730_000_000_000L,
                                finished = false,
                                playbackSpeed = 1.0f,
                                currentChapterId = null,
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                    // No position may be persisted for a book the caller can't reach.
                    deps.positionRepo.getPosition("member", "private-book").shouldBeNull()
                }
            }
        }

        test("recordListeningEvent returns NotFound and accrues no stats for a member on an unreachable book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("private-book"))
                    deps.collectionRepo.upsert(playbackCollection("private-col", owner = "stranger"))
                    deps.collectionBookRepo.upsert(playbackMembership("private-col", "private-book"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val startedAt = 1_779_451_200_000L
                    val result =
                        service.recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "evt-x",
                                bookId = "private-book",
                                startPositionMs = 0L,
                                endPositionMs = 60_000L,
                                startedAt = startedAt,
                                endedAt = startedAt + 60_000L,
                                playbackSpeed = 1.0f,
                                tz = "UTC",
                                deviceLabel = null,
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                    // Stats must not accrue against an inaccessible book.
                    deps.statsRepo.getForUser("member").shouldBeNull()
                }
            }
        }

        test("getPosition returns NotFound for a member on an unreachable book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("private-book"))
                    deps.collectionRepo.upsert(playbackCollection("private-col", owner = "stranger"))
                    deps.collectionBookRepo.upsert(playbackMembership("private-col", "private-book"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val result = service.getPosition(BookId("private-book"))
                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        // ─── getStats / recordListeningEvent ──────────────────────────────────────

        test("getStats returns AppResult.Success(null) for a user with no listening history") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = buildDeps(db)
                runTest {
                    val service = deps.service(db, "u1")

                    val result = service.getStats()
                    val success = result.shouldBeInstanceOf<AppResult.Success<UserStatsSyncPayload?>>()
                    success.data.shouldBeNull()
                }
            }
        }

        test("getStats returns non-null stats after recordListeningEvent with correct totalSecondsAllTime") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("book-1"))
                    deps.makeReachable("book-1", "u1")
                    val service = deps.service(db, "u1")

                    val startedAt = 1_779_451_200_000L
                    val endedAt = startedAt + 3_600_000L // 3600 seconds
                    service
                        .recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "evt-1",
                                bookId = "book-1",
                                startPositionMs = 0L,
                                endPositionMs = 3_600_000L,
                                startedAt = startedAt,
                                endedAt = endedAt,
                                playbackSpeed = 1.0f,
                                tz = "UTC",
                                deviceLabel = null,
                            ),
                        ).shouldBeInstanceOf<AppResult.Success<ListeningEventSyncPayload>>()

                    val statsResult = service.getStats()
                    val stats = statsResult.shouldBeInstanceOf<AppResult.Success<UserStatsSyncPayload?>>().data.shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 3600L
                }
            }
        }

        test("recordListeningEvent stores event under the authenticated principal's userId") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("book-1"))
                    deps.makeReachable("book-1", "u1")
                    val service = deps.service(db, "u1")

                    val startedAt = 1_779_451_200_000L
                    service.recordListeningEvent(
                        RecordListeningEventRequest(
                            id = "evt-1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 60_000L,
                            startedAt = startedAt,
                            endedAt = startedAt + 60_000L,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = null,
                        ),
                    )

                    // u1 has stats; u2 has none — event was scoped to principal u1
                    val u1Stats = deps.statsRepo.getForUser("u1").shouldNotBeNull()
                    u1Stats.totalSecondsAllTime shouldBe 60L
                    deps.statsRepo.getForUser("u2").shouldBeNull()
                }
            }
        }

        // ─── timezone refresh on live ingest ─────────────────────────────────────

        test("a live listening event refreshes the user's home timezone") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1") // starts with timezone = "UTC" (column default)
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("book-tz"))
                    deps.makeReachable("book-tz", "u1")
                    val service = deps.service(db, "u1")

                    val startedAt = 1_779_451_200_000L
                    service
                        .recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "evt-tz",
                                bookId = "book-tz",
                                startPositionMs = 0L,
                                endPositionMs = 60_000L,
                                startedAt = startedAt,
                                endedAt = startedAt + 60_000L,
                                playbackSpeed = 1.0f,
                                tz = "America/New_York",
                                deviceLabel = null,
                            ),
                        ).shouldBeInstanceOf<AppResult.Success<ListeningEventSyncPayload>>()

                    val tz = suspendTransaction(db) { UserEntity.findById("u1")?.timezone }
                    tz shouldBe "America/New_York"
                }
            }
        }

        test("a failed access-gate check does not update the user's timezone") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("member")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("private-book"))
                    deps.collectionRepo.upsert(playbackCollection("private-col", owner = "stranger"))
                    deps.collectionBookRepo.upsert(playbackMembership("private-col", "private-book"))

                    val service = deps.service(db, userId = "member", role = UserRole.MEMBER)

                    val startedAt = 1_779_451_200_000L
                    service
                        .recordListeningEvent(
                            RecordListeningEventRequest(
                                id = "evt-gate",
                                bookId = "private-book",
                                startPositionMs = 0L,
                                endPositionMs = 60_000L,
                                startedAt = startedAt,
                                endedAt = startedAt + 60_000L,
                                playbackSpeed = 1.0f,
                                tz = "America/Chicago",
                                deviceLabel = null,
                            ),
                        ).shouldBeInstanceOf<AppResult.Failure>()

                    // Access was denied — timezone must not have been updated.
                    val tz = suspendTransaction(db) { UserEntity.findById("member")?.timezone }
                    tz shouldBe "UTC"
                }
            }
        }

        test("re-recording the same event id is idempotent: payload fields unchanged, revision advances") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                val deps = buildDeps(db)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("book-1"))
                    deps.makeReachable("book-1", "u1")
                    val service = deps.service(db, "u1")

                    val startedAt = 1_779_451_200_000L
                    val request =
                        RecordListeningEventRequest(
                            id = "evt-idem",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 30_000L,
                            startedAt = startedAt,
                            endedAt = startedAt + 30_000L,
                            playbackSpeed = 1.5f,
                            tz = "UTC",
                            deviceLabel = "iPhone",
                        )

                    val first =
                        service
                            .recordListeningEvent(request)
                            .shouldBeInstanceOf<AppResult.Success<ListeningEventSyncPayload>>()
                            .data
                    val second =
                        service
                            .recordListeningEvent(request)
                            .shouldBeInstanceOf<AppResult.Success<ListeningEventSyncPayload>>()
                            .data

                    // Domain fields must be identical across both calls
                    second.id shouldBe first.id
                    second.bookId shouldBe first.bookId
                    second.startPositionMs shouldBe first.startPositionMs
                    second.endPositionMs shouldBe first.endPositionMs
                    second.startedAt shouldBe first.startedAt
                    second.endedAt shouldBe first.endedAt
                    second.playbackSpeed shouldBe first.playbackSpeed
                    // Revision advances on the second upsert (append-only repo still bumps revision).
                    // The global revision counter may skip values (stats upsert consumes revisions too),
                    // so we assert strictly greater-than rather than exact increment.
                    second.revision shouldBeGreaterThan first.revision
                }
            }
        }
    })

private fun bookWithThreeFiles(bookId: String): BookSyncPayload =
    BookSyncPayload(
        id = bookId,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
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

private fun playbackCollection(
    id: String,
    owner: String,
    isInbox: Boolean = false,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = isInbox,
        revision = 0L,
        updatedAt = 0L,
    )

private fun playbackMembership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun playbackShare(
    id: String,
    collectionId: String,
    userId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = userId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
        deletedAt = null,
    )
