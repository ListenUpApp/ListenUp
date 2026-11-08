package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

private const val BOOK_A = "book-a"
private const val BOOK_B = "book-b"
private const val USER_ID = "user-test-1"
private const val SPEED = 1.0f

/**
 * The open span is the record of one listen to one book. [ListeningEventRecorder] never lets a later call
 * erase it: not a second play, and not a pause or a tick that belongs to a different book.
 */
class ListeningEventRecorderSpanOwnershipTest :
    FunSpec({

        test("a play over this process's own open span finalizes that span instead of erasing it") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueued ->
                    recorder.onPlay(BOOK_A, positionMs = 0L, playbackSpeed = SPEED)
                    now = 1_801_000L
                    recorder.onPeriodicTick(BOOK_A, 1_800_000L)

                    // No pause reached the recorder (a stall, a lost signal): the next play arrives over the open span.
                    now = 2_701_000L
                    recorder.onPlay(BOOK_A, positionMs = 2_700_000L, playbackSpeed = SPEED)

                    val event = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_A).single()
                    event.startPositionMs shouldBe 0L
                    // Closed where it was last known to be, at the time it was last known to be there.
                    event.endPositionMs shouldBe 1_800_000L
                    event.endedAt shouldBe 1_801_000L
                    enqueued.size shouldBe 1

                    db.tentativeSpanDao().countRows() shouldBe 1
                    val live = db.tentativeSpanDao().get().shouldNotBeNull()
                    live.id shouldNotBe event.id
                    live.startPositionMs shouldBe 2_700_000L
                }
            }
        }

        // Android's car and voice paths name the next book before the outgoing book's pause arrives, so that pause
        // comes in labelled with the new book and carrying a place in it.
        test("a pause for another book closes the open span at its own last known position, never the other book's") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueued ->
                    recorder.onPlay(BOOK_A, positionMs = 3_000_000L, playbackSpeed = SPEED)
                    now = 31_000L
                    recorder.onPeriodicTick(BOOK_A, 3_030_000L)

                    now = 45_000L
                    recorder.onPause(BOOK_B, positionMs = 120_000L)

                    val event = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_A).single()
                    event.startPositionMs shouldBe 3_000_000L
                    event.endPositionMs shouldBe 3_030_000L
                    // Book A did sound until the switch.
                    event.endedAt shouldBe 45_000L
                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_B).size shouldBe 0
                    enqueued.size shouldBe 1
                    db.tentativeSpanDao().countRows() shouldBe 0
                }
            }
        }

        test("a tick for another book never moves the open span") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, _ ->
                    recorder.onPlay(BOOK_A, positionMs = 3_000_000L, playbackSpeed = SPEED)
                    now = 31_000L
                    recorder.onPeriodicTick(BOOK_B, 30_000L)

                    val span = db.tentativeSpanDao().get().shouldNotBeNull()
                    span.bookId shouldBe BOOK_A
                    span.currentPositionMs shouldBe 3_000_000L
                    span.lastHeartbeatAt shouldBe 1_000L
                }
            }
        }

        test("the next book's play after a switch with no pause closes the outgoing book's span first") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, _ ->
                    recorder.onPlay(BOOK_A, positionMs = 0L, playbackSpeed = SPEED)
                    now = 31_000L
                    recorder.onPeriodicTick(BOOK_A, 30_000L)

                    now = 40_000L
                    recorder.onPlay(BOOK_B, positionMs = 500_000L, playbackSpeed = SPEED)

                    db
                        .listeningEventDao()
                        .getByBookForUser(USER_ID, BOOK_A)
                        .single()
                        .endPositionMs shouldBe 30_000L
                    db
                        .tentativeSpanDao()
                        .get()
                        .shouldNotBeNull()
                        .bookId shouldBe BOOK_B
                    db.tentativeSpanDao().countRows() shouldBe 1
                }
            }
        }
    })
