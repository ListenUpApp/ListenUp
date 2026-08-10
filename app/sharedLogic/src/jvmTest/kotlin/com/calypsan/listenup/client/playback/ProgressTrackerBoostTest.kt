package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.core.BookId
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private val BOOK_ID = BookId("book-1")

/**
 * Boost-domain analogues of [PlaybackManagerSpeedTest]'s speed-path pins, exercised
 * directly against [ProgressTracker] (no [PlaybackManager] in this chain). Each handler
 * is a bare launch+save+fold — unlike [ProgressTracker.onSpeedChanged] /
 * [ProgressTracker.onSpeedReset], boost fields do not live in [SessionState], so there
 * is no session-state bookkeeping to pin here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerBoostTest :
    FunSpec({
        test("onVolumeBoostChanged saves PlaybackUpdate.VolumeBoost") {
            runTest {
                val positionRepository = defaultPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positionRepository)

                tracker.onVolumeBoostChanged(BOOK_ID, positionMs = 5_000L, newBoostDb = 6f)
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(1)) {
                    positionRepository.savePlaybackState(
                        any(),
                        matches<PlaybackUpdate>({ "VolumeBoost(boostDb=6.0, custom=true, positionMs=5000)" }) {
                            it is PlaybackUpdate.VolumeBoost &&
                                it.boostDb == 6f &&
                                it.custom &&
                                it.positionMs == 5_000L
                        },
                    )
                }
            }
        }

        test("onBoostReset saves PlaybackUpdate.BoostReset") {
            runTest {
                val positionRepository = defaultPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positionRepository)

                tracker.onBoostReset(BOOK_ID, positionMs = 7_000L, defaultBoostDb = 3f)
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(1)) {
                    positionRepository.savePlaybackState(
                        any(),
                        matches<PlaybackUpdate>({ "BoostReset(defaultBoostDb=3.0, positionMs=7000)" }) {
                            it is PlaybackUpdate.BoostReset &&
                                it.defaultBoostDb == 3f &&
                                it.positionMs == 7_000L
                        },
                    )
                }
            }
        }

        test("onMeasuredGain saves PlaybackUpdate.MeasuredGain") {
            runTest {
                val positionRepository = defaultPositionRepository()
                val tracker = buildProgressTracker(scope = this, positionRepository = positionRepository)

                tracker.onMeasuredGain(BOOK_ID, positionMs = 9_000L, gainDb = -2.5f)
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(1)) {
                    positionRepository.savePlaybackState(
                        any(),
                        matches<PlaybackUpdate>({ "MeasuredGain(gainDb=-2.5, positionMs=9000)" }) {
                            it is PlaybackUpdate.MeasuredGain &&
                                it.gainDb == -2.5f &&
                                it.positionMs == 9_000L
                        },
                    )
                }
            }
        }
    })
