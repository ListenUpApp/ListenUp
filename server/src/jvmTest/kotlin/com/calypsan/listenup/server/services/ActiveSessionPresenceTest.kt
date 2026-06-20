@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.ActiveSessionTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Presence-projection behaviour for [ActiveSessionRepository] after it stopped being a
 * client-sync domain: the create/refresh write and the two read queries that back the
 * server-derived "currently listening" / "readers of this book" presence surfaces.
 */
class ActiveSessionPresenceTest :
    FunSpec({

        fun Database.seedSession(
            sessionId: String,
            userId: String,
            bookId: String,
            startedAt: Long = 1_730_000_000_000L,
            updatedAt: Long = 1_730_000_000_000L,
        ) {
            transaction(this) {
                ActiveSessionTable.insert {
                    it[ActiveSessionTable.sessionId] = sessionId
                    it[ActiveSessionTable.userId] = userId
                    it[ActiveSessionTable.bookId] = bookId
                    it[ActiveSessionTable.startedAt] = startedAt
                    it[ActiveSessionTable.updatedAt] = updatedAt
                    it[ActiveSessionTable.createdAt] = updatedAt
                    it[ActiveSessionTable.revision] = 0L
                    it[ActiveSessionTable.deletedAt] = null
                }
            }
        }

        fun Database.updatedAtOf(
            userId: String,
            bookId: String,
        ): Long =
            transaction(this) {
                ActiveSessionTable
                    .selectAll()
                    .where { (ActiveSessionTable.userId eq userId) and (ActiveSessionTable.bookId eq bookId) }
                    .single()[ActiveSessionTable.updatedAt]
            }

        fun Database.liveRowCount(
            userId: String,
            bookId: String,
        ): Long =
            transaction(this) {
                ActiveSessionTable
                    .selectAll()
                    .where {
                        (ActiveSessionTable.userId eq userId) and
                            (ActiveSessionTable.bookId eq bookId) and
                            ActiveSessionTable.deletedAt.isNull()
                    }.count()
            }

        test("startOrRefresh inserts one live row and returns true; a second call returns false and bumps updatedAt") {
            withInMemoryDatabase {
                val repo =
                    ActiveSessionRepository(
                        db = this.asSqlDatabase(),
                        bus = ChangeBus(),
                        clock = FixedClock(Instant.fromEpochMilliseconds(1000L)),
                    )
                val laterRepo =
                    ActiveSessionRepository(
                        db = this.asSqlDatabase(),
                        bus = ChangeBus(),
                        clock = FixedClock(Instant.fromEpochMilliseconds(5000L)),
                    )
                runTest {
                    repo.startOrRefresh("u1", "book-1") shouldBe true
                    liveRowCount("u1", "book-1") shouldBe 1L
                    updatedAtOf("u1", "book-1") shouldBe 1000L

                    laterRepo.startOrRefresh("u1", "book-1") shouldBe false
                    liveRowCount("u1", "book-1") shouldBe 1L
                    updatedAtOf("u1", "book-1") shouldBe 5000L
                }
            }
        }

        test("listCurrentlyListening returns live rows for all users except the excluded one") {
            withInMemoryDatabase {
                seedSession("s1", "u1", "book-1")
                seedSession("s2", "u2", "book-2")
                seedSession("s3", "u3", "book-3")
                val repo = ActiveSessionRepository(db = this.asSqlDatabase(), bus = ChangeBus())
                runTest {
                    val rows = repo.listCurrentlyListening(excludeUserId = "u2")
                    rows.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u3")
                }
            }
        }

        test("listReadersForBook returns live rows on that book except the excluded user") {
            withInMemoryDatabase {
                seedSession("s1", "u1", "book-1")
                seedSession("s2", "u2", "book-1")
                seedSession("s3", "u3", "book-1")
                seedSession("s4", "u4", "book-other")
                val repo = ActiveSessionRepository(db = this.asSqlDatabase(), bus = ChangeBus())
                runTest {
                    val rows = repo.listReadersForBook(bookId = "book-1", excludeUserId = "u2")
                    rows.map { it.userId } shouldContainExactlyInAnyOrder listOf("u1", "u3")
                    rows.forEach { it.bookId shouldBe "book-1" }
                }
            }
        }

        test("deleteForUserBook removes the live row(s) for that pair only") {
            withInMemoryDatabase {
                seedSession("s1", "u1", "book-1")
                seedSession("s2", "u1", "book-2")
                seedSession("s3", "u2", "book-1")
                val repo = ActiveSessionRepository(db = this.asSqlDatabase(), bus = ChangeBus())
                runTest {
                    repo.deleteForUserBook("u1", "book-1")

                    liveRowCount("u1", "book-1") shouldBe 0L
                    val survivors = repo.listCurrentlyListening(excludeUserId = "none").map { it.userId to it.bookId }
                    survivors shouldContainExactlyInAnyOrder listOf("u1" to "book-2", "u2" to "book-1")
                }
            }
        }
    })
