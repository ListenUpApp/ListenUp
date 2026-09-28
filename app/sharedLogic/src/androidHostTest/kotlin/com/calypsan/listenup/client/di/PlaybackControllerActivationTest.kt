package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Pins WHEN the playback service gets bound on Android.
 *
 * On Android the first [PlaybackController.acquire] binds `PlaybackService`, which builds ExoPlayer
 * and the MediaSession. Doing that as a `createdAtStart` single put it ahead of the first frame and
 * on every push or WorkManager wake. Loading the real [playbackPresentationModule] and building its
 * eager instances — exactly what `startKoin` does — must therefore acquire nothing; only
 * [activatePlaybackController] (called by `MainActivity` and `PlaybackService.onCreate`) does, and
 * only once however often it is called.
 */
class PlaybackControllerActivationTest :
    FunSpec({
        // Koin caches a single's instance on the module's own definition, which every
        // KoinApplication loading that module shares, so each graph must be closed even when an
        // assertion fails — or the next test is served this test's activator.
        fun withGraph(
            controller: FakePlaybackController,
            block: (Koin) -> Unit,
        ) {
            val app =
                koinApplication {
                    modules(
                        playbackPresentationModule,
                        module { single<PlaybackController> { controller } },
                    )
                    createEagerInstances()
                }
            try {
                block(app.koin)
            } finally {
                app.close()
            }
        }

        test("starting Koin does not acquire the playback controller") {
            val controller = FakePlaybackController()

            withGraph(controller) { }

            controller.acquireCount shouldBe 0
        }

        test("activatePlaybackController acquires exactly once, however often it is called") {
            val controller = FakePlaybackController()

            withGraph(controller) { koin ->
                koin.activatePlaybackController()
                koin.activatePlaybackController()
            }

            controller.acquireCount shouldBe 1
        }
    })
