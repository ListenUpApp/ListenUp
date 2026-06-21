@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Presence-projection behaviour for [ActiveSessionRepository] after it stopped being a
 * client-sync domain: the create/refresh write and the two read queries that back the
 * server-derived "currently listening" / "readers of this book" presence surfaces.
 */
class ActiveSessionPresenceTest :
    FunSpec({

        fun ListenUpDatabase.seedSession(
            sessionId: String,
            userId: String,
            bookId: String,
            startedAt: Long = 1_730_000_000_000L,
            updatedAt: Long = 1_730_000_000_000L,
        ) {
            activeSessionsQueries.insert(
                session_id = sessionId,
                user_id = userId,
                book_id = bookId,
                started_at = startedAt,
                created_at = updatedAt,
                updated_at = updatedAt,
            )
        }

        fun ListenUpDatabase.updatedAtOf(
            userId: String,
            bookId: String,
        ): Long =
            activeSessionsQueries
                .selectLiveForUserBook(user_id = userId, book_id = bookId)
                .executeAsOneOrNull()
                ?.updated_at
                ?: error("no live session for ($userId, $bookId)")

        fun ListenUpDatabase.liveRowCount(
            userId: String,
            bookId: String,
        ): Long =
            if (activeSessionsQueries
                    .selectLiveForUserBook(user_id = userId, book_id = bookId)
                    .executeAsOneOrNull() != null
            ) {
                1L
            } else {
                0L
            }

        test("startOrRefresh inserts one live row and returns true; a second call returns false and bumps updatedAt") {
            withSqlDatabase {
                val repo =
                    ActiveSessionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        clock = FixedClock(Instant.fromEpochMilliseconds(1000L)),
                    )
                val laterRepo =
                    ActiveSessionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        clock = FixedClock(Instant.fromEpochMilliseconds(5000L)),
                    )
                runTest {
                    repo.startOrRefresh("u1", "book-1") shouldBe true
                    sql.liveRowCount("u1", "book-1") shouldBe 1L
                    sql.updatedAtOf("u1", "book-1") shouldBe 1000L

                    laterRepo.startOrRefresh("u1", "book-1") shouldBe false
                    sql.liveRowCount("u1", "book-1") shouldBe 1L
                    sql.updatedAtOf("u1", "book-1") shouldBe 5000L
                }
            }
        }

        test("listCurrentlyListening returns live rows for all users except the excluded one") {
            withSqlDatabase {
                sql.seedSession("s1", "u1", "book-1")
                sql.seedSession("s2", "u2", "book-2")
                sql.seedSession("s3", "u3", "book-3")
                val repo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                runTest {
                    val rows = repo.listCurrentlyListening(excludeUserId = "u2")
                    rows.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u3")
                }
            }
        }

        test("listReadersForBook returns live rows on that book except the excluded user") {
            withSqlDatabase {
                sql.seedSession("s1", "u1", "book-1")
                sql.seedSession("s2", "u2", "book-1")
                sql.seedSession("s3", "u3", "book-1")
                sql.seedSession("s4", "u4", "book-other")
                val repo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                runTest {
                    val rows = repo.listReadersForBook(bookId = "book-1", excludeUserId = "u2")
                    rows.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u3")
                    rows.forEach { it.bookId shouldBe "book-1" }
                }
            }
        }

        test("deleteForUserBook removes the live row(s) for that pair only") {
            withSqlDatabase {
                sql.seedSession("s1", "u1", "book-1")
                sql.seedSession("s2", "u1", "book-2")
                sql.seedSession("s3", "u2", "book-1")
                val repo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                runTest {
                    repo.deleteForUserBook("u1", "book-1")

                    sql.liveRowCount("u1", "book-1") shouldBe 0L
                    val survivors = repo.listCurrentlyListening(excludeUserId = "none").map { it.userId to it.bookId }
                    survivors shouldContainExactlyInAnyOrder listOf("u1" to "book-2", "u2" to "book-1")
                }
            }
        }
    })
