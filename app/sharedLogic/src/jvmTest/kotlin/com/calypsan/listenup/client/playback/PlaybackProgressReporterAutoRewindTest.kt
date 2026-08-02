package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Regression tests for #1220: auto-rewind must also apply on in-session pause→resume, not
 * only at prepare-time (see [PlaybackPreparer.resumeStartPositionMs] / [autoRewindMs]).
 *
 * [PlaybackProgressReporter.notePlaybackPaused] / [notePlaybackResumed] are the shared decision
 * point for the ladder itself — reused verbatim from [autoRewindMs], never forked. Manager-level
 * wiring (the real [PlaybackManagerImpl.setPlaying] seam, [PlaybackManagerImpl.activateBook]'s
 * reset, the built-in-player actuator) is covered separately in
 * [PlaybackManagerAutoRewindOnResumeTest].
 */
class PlaybackProgressReporterAutoRewindTest :
    FunSpec({

        fun buildReporter(
            autoRewindEnabled: Boolean = true,
            nowMillis: () -> Long,
        ): Pair<PlaybackProgressReporter, FakeProgressTracker> {
            val tracker =
                FakeProgressTracker(
                    downloadRepository = mock<DownloadRepository>(),
                    positionRepository = FakePlaybackPositionRepository(),
                    scope = CoroutineScope(Job()),
                )
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(autoRewindEnabled)
            val reporter =
                PlaybackProgressReporter(
                    progressTracker = tracker,
                    recorder = null,
                    scope = CoroutineScope(Job()),
                    localPreferences = localPreferences,
                    nowMillis = nowMillis,
                )
            return reporter to tracker
        }

        // ── ladder decision at the seam ──────────────────────────────────────────────────

        test("pausing three minutes then resuming applies the short-break rung") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            var seekedMs: Long? = null
            reporter.onAutoRewindSeek = { seekedMs = it }

            reporter.notePlaybackPaused()
            now += 3 * 60_000L
            reporter.notePlaybackResumed()

            seekedMs shouldBe 5_000L
        }

        test("pausing two seconds then resuming does not rewind") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            var seekedMs: Long? = null
            reporter.onAutoRewindSeek = { seekedMs = it }

            reporter.notePlaybackPaused()
            now += 2_000L
            reporter.notePlaybackResumed()

            seekedMs.shouldBeNull()
        }

        test("auto-rewind disabled never seeks, however long the pause") {
            var now = 0L
            val (reporter, _) = buildReporter(autoRewindEnabled = false, nowMillis = { now })
            var seekedMs: Long? = null
            reporter.onAutoRewindSeek = { seekedMs = it }

            reporter.notePlaybackPaused()
            now += 2 * 86_400_000L
            reporter.notePlaybackResumed()

            seekedMs.shouldBeNull()
        }

        test("resuming without ever pausing does not seek") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            var seeks = 0
            reporter.onAutoRewindSeek = { seeks++ }

            reporter.notePlaybackResumed()

            seeks shouldBe 0
        }

        // ── no stacking with the prepare-time rewind ─────────────────────────────────────

        test("resetAutoRewindWindow suppresses a stale pause from a previous book/session") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            var seeks = 0
            reporter.onAutoRewindSeek = { seeks++ }

            // Book A pauses; a long time passes.
            reporter.notePlaybackPaused()
            now += 2 * 86_400_000L

            // Book B activates — its own prepare-time rewind already ran via PlaybackPreparer.
            // The stale window from book A must not ALSO fire a transition rewind here.
            reporter.resetAutoRewindWindow()
            reporter.notePlaybackResumed()

            seeks shouldBe 0
        }

        test("repeated pause/resume cycles apply the ladder independently, never compounding") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            val seeks = mutableListOf<Long>()
            reporter.onAutoRewindSeek = { seeks += it }

            reporter.notePlaybackPaused()
            now += 90 * 60_000L // 90 min away -> hour-to-day rung
            reporter.notePlaybackResumed()

            reporter.notePlaybackPaused()
            now += 30_000L // 30s away -> no rung
            reporter.notePlaybackResumed()

            reporter.notePlaybackPaused()
            now += 10 * 60_000L // 10 min away -> short-break rung
            reporter.notePlaybackResumed()

            seeks shouldBe listOf(15_000L, 5_000L)
        }

        // ── never persisted ───────────────────────────────────────────────────────────────

        test("the transition rewind never flows into position or listening-event persistence") {
            var now = 0L
            val (reporter, tracker) = buildReporter(nowMillis = { now })
            reporter.onAutoRewindSeek = { }

            reporter.notePlaybackPaused()
            now += 2 * 86_400_000L
            reporter.notePlaybackResumed()

            tracker.onPlaybackStartedCalls shouldBe emptyList()
            tracker.onPlaybackPausedCalls shouldBe emptyList()
        }

        test("a computed rewind with no actuator registered is a silent no-op") {
            var now = 0L
            val (reporter, _) = buildReporter(nowMillis = { now })
            // onAutoRewindSeek left null (default) — must not throw.
            reporter.notePlaybackPaused()
            now += 2 * 86_400_000L
            reporter.notePlaybackResumed()
        }
    })
