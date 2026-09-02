@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * An import is a record of listening that already happened. It is not listening.
 *
 * `recordAllForImport` fired [ActiveSessionRepository.startOrRefresh] for every unfinished row, and
 * presence is stamped with the wall clock rather than the play instant — so importing someone's
 * history announced them as listening right now to a book they last opened months or years ago,
 * one bogus live row per imported book. "What Others Are Listening To" then showed each imported
 * user on whichever of those rows the import happened to write last: an arbitrary old book,
 * labelled as playing now. Two people's histories were imported and that is exactly what appeared.
 */
class ImportPresenceTest :
    FunSpec({
        fun historicalRow(
            userId: String,
            bookId: String,
            lastPlayedAt: Long,
            finished: Boolean = false,
        ) = ImportPositionWrite(
            userId = userId,
            bookId = bookId,
            positionMs = 60_000L,
            lastPlayedAt = lastPlayedAt,
            finished = finished,
            playbackSpeed = 1.0f,
            currentChapterId = null,
        )

        test("importing a history makes nobody look like they are listening right now") {
            withSqlDatabase {
                val presence = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = presence,
                    )
                runTest {
                    repo.recordAllForImport(
                        listOf(
                            // Four months ago, unfinished — the shape that used to be announced as live.
                            historicalRow("u1", "book-old", 1_777_000_000_000L),
                            historicalRow("u1", "book-older", 1_770_000_000_000L),
                            historicalRow("u2", "book-ancient", 1_717_000_000_000L),
                        ),
                    )

                    presence.listCurrentlyListening(excludeUserId = "nobody", liveSince = 0L).shouldBeEmpty()
                }
            }
        }

        test("an import that finishes a book still clears a session the reader had open") {
            withSqlDatabase {
                val presence = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = presence,
                    )
                runTest {
                    // A genuine live session, from actually playing in ListenUp.
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 100L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    presence.listCurrentlyListening(excludeUserId = "nobody", liveSince = 0L).size shouldBe 1

                    // The import says they finished it elsewhere — the open session is no longer true.
                    repo.recordAllForImport(listOf(historicalRow("u1", "book-1", 200L, finished = true)))

                    presence.listCurrentlyListening(excludeUserId = "nobody", liveSince = 0L).shouldBeEmpty()
                }
            }
        }
    })
