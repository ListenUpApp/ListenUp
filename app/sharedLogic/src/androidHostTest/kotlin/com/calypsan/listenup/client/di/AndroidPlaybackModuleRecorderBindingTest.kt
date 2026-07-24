package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.test.di.KoinTestRule
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * POSITION-05 — pins the single-recorder DI invariant for Android by resolving
 * [PlaybackProgressReporter] from the REAL [androidPlaybackModule], not a hand-built
 * fixture.
 *
 * `PlaybackService` already drives `ListeningEventRecorder` directly to integrate with
 * Media3, so [androidPlaybackModule] MUST bind [PlaybackProgressReporter] with a `null`
 * recorder — a careless module edit that starts resolving a recorder here would silently
 * double-record every listening span (see [PlaybackProgressReporter]'s class KDoc). This
 * test only fakes [androidPlaybackModule]'s non-playback dependencies
 * ([com.calypsan.listenup.client.playback.ProgressTracker], [CoroutineScope]); the module
 * under test is loaded unmodified. No Android [android.content.Context] is required —
 * `PlaybackProgressReporter`'s own binding needs none — so this runs on the plain JVM
 * androidHostTest target without Robolectric, matching [KoinModuleVerifyTest]'s pattern.
 */
class AndroidPlaybackModuleRecorderBindingTest :
    FunSpec({
        val fixtureModule =
            module {
                single<ProgressTracker> {
                    FakeProgressTracker(
                        downloadRepository = FakeDownloadRepository(),
                        positionRepository = FakePlaybackPositionRepository(),
                        scope = get(),
                    )
                }
                single { CoroutineScope(SupervisorJob()) }
            }
        val koinRule = KoinTestRule(listOf(fixtureModule, androidPlaybackModule))

        beforeTest { koinRule.setUp() }
        afterTest { koinRule.tearDown() }

        test("androidPlaybackModule binds PlaybackProgressReporter with recorder = null") {
            val reporter = KoinPlatform.getKoin().get<PlaybackProgressReporter>()
            reporter.recorderForTest().shouldBeNull()
        }
    })
