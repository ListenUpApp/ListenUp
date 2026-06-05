@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class RecordPositionPresenceTest :
    FunSpec({

        test("recordPosition (live, not finished) creates a live active_sessions row") {
            withInMemoryDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = this, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = this,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 1_000L,
                            lastPlayedAt = 100L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val readers = activeSessionRepo.listReadersForBook("book-1", excludeUserId = "someoneElse")
                    readers shouldHaveSize 1
                    readers.first().userId shouldBe "u1"
                    readers.first().bookId shouldBe "book-1"
                }
            }
        }

        test("a second live recordPosition for the same (user, book) refreshes — still exactly one row") {
            withInMemoryDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = this, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = this,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 100L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 2_000L,
                        lastPlayedAt = 200L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    activeSessionRepo.listReadersForBook("book-1", excludeUserId = "someoneElse") shouldHaveSize 1
                }
            }
        }

        test("recordPosition finished=true on the finish-flip removes the active_sessions row") {
            withInMemoryDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = this, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = this,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 100L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    activeSessionRepo.listReadersForBook("book-1", excludeUserId = "someoneElse") shouldHaveSize 1

                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 2_000L,
                        lastPlayedAt = 200L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    activeSessionRepo.listReadersForBook("book-1", excludeUserId = "someoneElse").shouldBeEmpty()
                }
            }
        }
    })
