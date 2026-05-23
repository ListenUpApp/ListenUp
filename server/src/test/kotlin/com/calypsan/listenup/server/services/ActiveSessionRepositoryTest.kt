@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ActiveSessionSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ActiveSessionRepositoryTest :
    FunSpec({

        fun activeSessionRepo() = ActiveSessionRepository(
            db = error("must be wired inside withInMemoryDatabase"),
            bus = ChangeBus(),
            registry = SyncRegistry(),
        )

        test("upsert inserts a new active-session row") {
            withInMemoryDatabase {
                val repo = ActiveSessionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val payload = ActiveSessionSyncPayload(
                        sessionId = "sess-1",
                        bookId = "book-1",
                        startedAt = 1_730_000_000_000L,
                        revision = 0L,
                        updatedAt = 0L,
                        createdAt = 0L,
                        deletedAt = null,
                    )
                    val result = repo.upsert(payload, userId = "u1")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val sessions = repo.getForUser("u1")
                    sessions shouldHaveSize 1
                    sessions.first().sessionId shouldBe "sess-1"
                    sessions.first().bookId shouldBe "book-1"
                }
            }
        }

        test("deleteForUserBook removes the matching row") {
            withInMemoryDatabase {
                val repo = ActiveSessionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        ActiveSessionSyncPayload(
                            sessionId = "sess-1",
                            bookId = "book-1",
                            startedAt = 1_730_000_000_000L,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        userId = "u1",
                    )

                    val result = repo.deleteForUserBook("u1", "book-1")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    repo.getForUser("u1").shouldBeEmpty()
                }
            }
        }

        test("deleteForUserBook is idempotent — no throw when row absent") {
            withInMemoryDatabase {
                val repo = ActiveSessionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Empty table — should not throw
                    val result = repo.deleteForUserBook("u1", "book-does-not-exist")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("deleteForUserBook only deletes the matching (userId, bookId) pair") {
            withInMemoryDatabase {
                val repo = ActiveSessionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // u1 listening to book-1
                    repo.upsert(
                        ActiveSessionSyncPayload(
                            sessionId = "sess-u1-b1",
                            bookId = "book-1",
                            startedAt = 1_730_000_000_000L,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        userId = "u1",
                    )
                    // u1 listening to book-2
                    repo.upsert(
                        ActiveSessionSyncPayload(
                            sessionId = "sess-u1-b2",
                            bookId = "book-2",
                            startedAt = 1_730_000_000_000L,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        userId = "u1",
                    )
                    // u2 listening to book-1
                    repo.upsert(
                        ActiveSessionSyncPayload(
                            sessionId = "sess-u2-b1",
                            bookId = "book-1",
                            startedAt = 1_730_000_000_000L,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        ),
                        userId = "u2",
                    )

                    repo.deleteForUserBook("u1", "book-1")

                    // (u1, book-1) gone; (u1, book-2) and (u2, book-1) survive
                    val u1Sessions = repo.getForUser("u1")
                    u1Sessions shouldHaveSize 1
                    u1Sessions.first().sessionId shouldBe "sess-u1-b2"

                    val u2Sessions = repo.getForUser("u2")
                    u2Sessions shouldHaveSize 1
                    u2Sessions.first().sessionId shouldBe "sess-u2-b1"
                }
            }
        }
    })
