package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.di.KoinTestRule
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * POSITION-05 — pins the single-recorder DI invariant for Desktop by resolving
 * [PlaybackProgressReporter] from the REAL [desktopPlaybackModule], not a hand-built
 * fixture.
 *
 * Desktop has no Media3 `PlaybackService`, so [PlaybackProgressReporter] is the only
 * driver of listening-event recording there — [desktopPlaybackModule] MUST bind it with a
 * non-null recorder, or Desktop listening history would silently stop being recorded. The
 * asymmetric counterpart to [AndroidPlaybackModuleRecorderBindingTest], which pins the
 * opposite (`null`) for Android.
 */
class DesktopPlaybackModuleRecorderBindingTest :
    FunSpec({
        test("desktopPlaybackModule binds PlaybackProgressReporter with a non-null recorder") {
            val db = createInMemoryTestDatabase()
            try {
                val recorder =
                    ListeningEventRecorder(
                        listeningEventDao = db.listeningEventDao(),
                        tentativeSpanDao = db.tentativeSpanDao(),
                        transactionRunner = RoomTransactionRunner(db),
                        enqueue = { _, _, _ -> },
                        currentUserId = { "user-test" },
                        deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "Test Device") },
                    )
                val fixtureModule =
                    module {
                        single<ProgressTracker> {
                            FakeProgressTracker(
                                downloadRepository = FakeDownloadRepository(),
                                positionRepository = FakePlaybackPositionRepository(),
                                scope = get(qualifier = named("playbackScope")),
                            )
                        }
                        single(qualifier = named("playbackScope")) { CoroutineScope(SupervisorJob()) }
                        single { recorder }
                    }
                val koinRule = KoinTestRule(listOf(fixtureModule, desktopPlaybackModule))
                koinRule.setUp()
                try {
                    val reporter = KoinPlatform.getKoin().get<PlaybackProgressReporter>()
                    reporter.recorderForTest().shouldNotBeNull()
                } finally {
                    koinRule.tearDown()
                }
            } finally {
                db.close()
            }
        }
    })
