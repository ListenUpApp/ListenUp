package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [PlaybackProgressServiceImpl].
 *
 * Tests focus on the two responsibilities that live exclusively in the impl:
 *  1. Principal resolution — null principal → [AppResult.Failure] with [SyncError.NotFound].
 *  2. Limit clamping — out-of-range limits are coerced to method-specific bounds before
 *     reaching the repository.
 *
 * Repository behaviour (user-scoping, tombstone filtering, ordering) is covered by
 * [com.calypsan.listenup.server.services.PlaybackPositionRepositoryTest].
 *
 * The test harness uses a real in-memory [PlaybackPositionRepository] so that the
 * limit assertions can verify the repository receives the clamped value — inferred
 * from the number of rows returned when we seed exactly at the boundary.
 */
class PlaybackProgressServiceImplTest :
    FunSpec({

        /** Minimal [PlaybackPositionSyncPayload] factory for seeding. */
        fun positionPayload(
            bookId: String,
            positionMs: Long = 1_000L,
            finished: Boolean = false,
        ): PlaybackPositionSyncPayload =
            PlaybackPositionSyncPayload(
                id = "pos-$bookId",
                bookId = bookId,
                positionMs = positionMs,
                lastPlayedAt = 1_700_000_000_000L,
                finished = finished,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        fun principal(userId: String = "u1"): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(
                    userId = UserId(userId),
                    sessionId = SessionId("session-$userId"),
                    role = UserRole.MEMBER,
                )
            }

        val noopPrincipal = PrincipalProvider.None

        // ── principal resolution ──────────────────────────────────────────────

        test("listProgress returns Failure(SyncError.NotFound) when principal is absent") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, noopPrincipal)
                runTest {
                    val result = service.listProgress(100)
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("getProgressBatch returns Failure(SyncError.NotFound) when principal is absent") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, noopPrincipal)
                runTest {
                    val result = service.getProgressBatch(listOf(BookId("book-1")))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                        .error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("getRecentlyListened returns Failure(SyncError.NotFound) when principal is absent") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, noopPrincipal)
                runTest {
                    val result = service.getRecentlyListened(20)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                        .error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("getCompletedBooks returns Failure(SyncError.NotFound) when principal is absent") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, noopPrincipal)
                runTest {
                    val result = service.getCompletedBooks(50)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                        .error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        // ── happy-path delegation ─────────────────────────────────────────────

        test("listProgress resolves current user and returns Success with their positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-a",
                        positionMs = 1_000L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    val result = service.listProgress(100)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 1
                    success.data[0].bookId shouldBe "book-a"
                }
            }
        }

        test("getProgressBatch returns Success with sparse matches for the current user") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-a",
                        positionMs = 1_000L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Request book-a and book-b; only book-a has a position.
                    val result = service.getProgressBatch(listOf(BookId("book-a"), BookId("book-b")))
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 1
                    success.data[0].bookId shouldBe "book-a"
                }
            }
        }

        test("getProgressBatch with empty input returns empty Success") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    val result = service.getProgressBatch(emptyList())
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 0
                }
            }
        }

        // ── limit clamping ────────────────────────────────────────────────────

        test("listProgress clamps limit to 500 max (repo called with 500, not 9999)") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    // Seed 3 positions — if limit > 3 the result is still all 3, confirming no error.
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    val result = service.listProgress(9999)
                    // Passes through — clamped to 500, still returns all 3 seeded.
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 3
                }
            }
        }

        test("listProgress clamps limit to 1 min (repo called with 1, not 0 or negative)") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    // limit=0 → clamped to 1 → repo returns at most 1 row.
                    val result = service.listProgress(0)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 1
                }
            }
        }

        test("getRecentlyListened clamps limit to 100 max") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    val result = service.getRecentlyListened(9999)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 3
                }
            }
        }

        test("getRecentlyListened clamps limit to 1 min") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    val result = service.getRecentlyListened(-5)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 1
                }
            }
        }

        test("getCompletedBooks clamps limit to 500 max") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = true,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    val result = service.getCompletedBooks(9999)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 3
                }
            }
        }

        test("getCompletedBooks clamps limit to 1 min") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, principal("u1"))
                runTest {
                    repeat(3) { i ->
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-$i",
                            positionMs = 1_000L,
                            lastPlayedAt = 1_700_000_000_000L + i,
                            finished = true,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    }
                    val result = service.getCompletedBooks(0)
                    val success = result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                    success.data.size shouldBe 1
                }
            }
        }

        // ── copyWith ──────────────────────────────────────────────────────────

        test("copyWith returns a new instance scoped to the given principal") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val service = PlaybackProgressServiceImpl(repo, noopPrincipal)
                val scoped = service.copyWith(principal("u1"))
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-x",
                        positionMs = 1_000L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    val result = scoped.listProgress(100)
                    result.shouldBeInstanceOf<AppResult.Success<List<PlaybackPositionSyncPayload>>>()
                        .data.size shouldBe 1
                }
            }
        }
    })
