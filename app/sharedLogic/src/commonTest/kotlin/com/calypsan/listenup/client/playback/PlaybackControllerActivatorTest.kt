package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.FakePlaybackController
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [PlaybackControllerActivator] is the process-lifetime replacement for the
 * `playbackController.acquire()` call that used to live in `NowPlayingViewModel.init` — the reason
 * that ViewModel had to be a Koin `single` instead of a `factory`. This is the RED-first
 * regression: acquisition must happen exactly once, independent of any ViewModel at all.
 */
class PlaybackControllerActivatorTest :
    FunSpec({
        test("construction acquires the PlaybackController exactly once") {
            val controller = FakePlaybackController()

            PlaybackControllerActivator(playbackController = controller)

            controller.acquireCount shouldBe 1
        }
    })
