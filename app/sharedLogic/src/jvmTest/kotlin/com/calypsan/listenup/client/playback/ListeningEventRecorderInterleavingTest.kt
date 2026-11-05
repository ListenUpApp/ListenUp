package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val BOOK_ID = "book-test-1"
private const val SPEED = 2.0f

/** Long enough for an unguarded call to run to its write; only a correctly serialized run waits it out. */
private const val CHANCE_TO_INTERLEAVE_MS = 500L

/**
 * Players report play and pause from separate coroutines, so a quick flip puts two recorder calls in flight at once.
 * Each spec holds the first call right after it read the span, and gives the second every chance to run in that gap.
 */
class ListeningEventRecorderInterleavingTest :
    FunSpec({
        test("a play landing while a pause is finalizing keeps its new span, so the listening after it is recorded") {
            runTest {
                lateinit var spans: HeldTentativeSpanDao
                var now = 1_000L
                withFixture(
                    nowMillisProvider = { now },
                    wrapTentativeSpanDao = { real -> HeldTentativeSpanDao(real).also { spans = it } },
                ) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, 0L, SPEED)
                    now = 31_000L
                    spans.holdNextRead()

                    withContext(Dispatchers.Default) {
                        val pause = launch { recorder.onPause(positionMs = 60_000L) }
                        spans.held.await()
                        val play = launch { recorder.onPlay(BOOK_ID, 60_000L, SPEED) }
                        withTimeoutOrNull(CHANCE_TO_INTERLEAVE_MS) { spans.written.await() }
                        spans.release.complete(Unit)
                        listOf(pause, play).forEach { it.join() }
                    }

                    db.tentativeSpanDao().countRows() shouldBe 1
                    db
                        .tentativeSpanDao()
                        .get()
                        .shouldNotBeNull()
                        .startPositionMs shouldBe 60_000L
                    db
                        .listeningEventDao()
                        .getByBookForUser("user-test-1", BOOK_ID)
                        .single()
                        .endPositionMs shouldBe 60_000L
                }
            }
        }

        test("a heartbeat in flight when a pause lands never brings the closed span back") {
            runTest {
                lateinit var spans: HeldTentativeSpanDao
                var now = 1_000L
                withFixture(
                    nowMillisProvider = { now },
                    wrapTentativeSpanDao = { real -> HeldTentativeSpanDao(real).also { spans = it } },
                ) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, 0L, SPEED)
                    now = 31_000L
                    spans.holdNextRead()

                    withContext(Dispatchers.Default) {
                        val tick = launch { recorder.onPeriodicTick(30_000L) }
                        spans.held.await()
                        val pause: Job = launch { recorder.onPause(positionMs = 60_000L) }
                        withTimeoutOrNull(CHANCE_TO_INTERLEAVE_MS) { pause.join() }
                        spans.release.complete(Unit)
                        listOf(tick, pause).forEach { it.join() }
                    }

                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }
    })

/** A real [TentativeSpanDao] that can stop one call between its read and whatever it does next. */
private class HeldTentativeSpanDao(
    private val delegate: TentativeSpanDao,
) : TentativeSpanDao by delegate {
    private val holding = AtomicBoolean(false)
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val written = CompletableDeferred<Unit>()

    /** The next [get] answers only once [release] completes, after saying so through [held]. */
    fun holdNextRead() = holding.set(true)

    override suspend fun get(): TentativeSpanEntity? {
        val span = delegate.get()
        if (holding.compareAndSet(true, false)) {
            held.complete(Unit)
            release.await()
        }
        return span
    }

    override suspend fun upsertSingleton(span: TentativeSpanEntity) {
        delegate.upsertSingleton(span)
        if (held.isCompleted) written.complete(Unit)
    }
}
