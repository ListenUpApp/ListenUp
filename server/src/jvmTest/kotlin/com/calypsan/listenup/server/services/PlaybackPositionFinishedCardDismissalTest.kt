package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val USER = "u1"
private const val BOOK = "book-1"
private const val FINISHED_AT = 1_730_000_000_000L
private const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000

/**
 * S2: the finish-card dismissal merges on its own rule — latest wins, null never erases — apart from the
 * `lastPlayedAt` guard every other position field obeys, because dismissing is not listening.
 */
class PlaybackPositionFinishedCardDismissalTest :
    FunSpec({
        fun repoOver(sql: ListenUpDatabase) = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

        suspend fun PlaybackPositionRepository.finish(lastPlayedAt: Long = FINISHED_AT) =
            recordPosition(
                userId = USER,
                bookId = BOOK,
                positionMs = 9_000L,
                lastPlayedAt = lastPlayedAt,
                finished = true,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                finishedAt = lastPlayedAt,
            )

        suspend fun PlaybackPositionRepository.dismiss(
            at: Long,
            lastPlayedAt: Long = FINISHED_AT,
        ) = recordPositionDetailed(
            userId = USER,
            bookId = BOOK,
            positionMs = 9_000L,
            lastPlayedAt = lastPlayedAt,
            finished = true,
            playbackSpeed = 1.0f,
            currentChapterId = null,
            finishedCardDismissedAt = at,
        )

        test("a dismissal that loses the lastPlayedAt guard still lands, moves nothing else, and syncs") {
            withSqlDatabase {
                runTest {
                    val repo = repoOver(sql)
                    repo.finish()
                    val before = repo.getPosition(USER, BOOK).shouldNotBeNull()

                    val result =
                        repo
                            .dismiss(at = FINISHED_AT + 2_000L)
                            .shouldBeInstanceOf<AppResult.Success<RecordPositionResult>>()
                            .data

                    result.accepted shouldBe false
                    result.position.finishedCardDismissedAt shouldBe FINISHED_AT + 2_000L
                    val after = repo.getPosition(USER, BOOK).shouldNotBeNull()
                    after.finishedCardDismissedAt shouldBe FINISHED_AT + 2_000L
                    after.lastPlayedAt shouldBe before.lastPlayedAt
                    after.positionMs shouldBe before.positionMs
                    after.finishedAt shouldBe before.finishedAt
                    after.revision shouldBeGreaterThan before.revision
                }
            }
        }

        test("a later position write that says nothing about the card keeps the dismissal") {
            withSqlDatabase {
                runTest {
                    val repo = repoOver(sql)
                    repo.finish()
                    repo.dismiss(at = FINISHED_AT + 2_000L)

                    repo.recordPosition(
                        userId = USER,
                        bookId = BOOK,
                        positionMs = 9_500L,
                        lastPlayedAt = FINISHED_AT + 10_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val stored = repo.getPosition(USER, BOOK).shouldNotBeNull()
                    stored.positionMs shouldBe 9_500L
                    stored.finishedCardDismissedAt shouldBe FINISHED_AT + 2_000L
                }
            }
        }

        test("an older dismissal never lowers a newer one") {
            withSqlDatabase {
                runTest {
                    val repo = repoOver(sql)
                    repo.finish()
                    repo.dismiss(at = FINISHED_AT + 5_000L)

                    repo.dismiss(at = FINISHED_AT + 3_000L)

                    repo.getPosition(USER, BOOK).shouldNotBeNull().finishedCardDismissedAt shouldBe FINISHED_AT + 5_000L
                }
            }
        }

        test("an accepted write that carries a dismissal stores it with the position") {
            withSqlDatabase {
                runTest {
                    val repo = repoOver(sql)
                    repo.finish()

                    repo
                        .dismiss(at = FINISHED_AT + 2_000L, lastPlayedAt = FINISHED_AT + 1_000L)
                        .shouldBeInstanceOf<AppResult.Success<RecordPositionResult>>()
                        .data
                        .accepted shouldBe true

                    val stored = repo.getPosition(USER, BOOK).shouldNotBeNull()
                    stored.lastPlayedAt shouldBe FINISHED_AT + 1_000L
                    stored.finishedCardDismissedAt shouldBe FINISHED_AT + 2_000L
                }
            }
        }

        test("a dismissal stamped far in the future is held to the server's clock") {
            withSqlDatabase {
                runTest {
                    val now = FINISHED_AT + 60_000L
                    val repo =
                        PlaybackPositionRepository(
                            db = sql,
                            bus = ChangeBus(),
                            registry = SyncRegistry(),
                            clock = MutableClock(Instant.fromEpochMilliseconds(now)),
                        )
                    repo.finish()

                    repo.dismiss(at = now + ONE_YEAR_MS)

                    repo.getPosition(USER, BOOK).shouldNotBeNull().finishedCardDismissedAt shouldBe now + SKEW_TOLERANCE_MS
                }
            }
        }
    })
