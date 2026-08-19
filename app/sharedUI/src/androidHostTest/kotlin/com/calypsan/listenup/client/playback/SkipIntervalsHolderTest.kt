package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pins the snapshot every platform playback surface reads its skip amounts from (#1300).
 *
 * Media3 asks for these from plain synchronous callbacks — `onCustomCommand`,
 * `createNotification`, `seekForward` — which can neither suspend nor block, exactly like the
 * `SystemStringsHolder` case. So the synced preference is collected once onto the service scope
 * and published into fields those callbacks read.
 *
 * The scope is `Dispatchers.Unconfined` so a `MutableStateFlow`'s replayed value lands
 * synchronously on `follow`, and each later change lands synchronously on assignment — the
 * assertions then say what the holder holds, with no scheduler timing in the way.
 */
class SkipIntervalsHolderTest :
    FunSpec({
        fun preferences(
            forward: MutableStateFlow<Int>,
            backward: MutableStateFlow<Int>,
        ): PlaybackPreferences {
            val preferences = mock<PlaybackPreferences>()
            every { preferences.observeDefaultSkipForwardSec() } returns forward
            every { preferences.observeDefaultSkipBackwardSec() } returns backward
            return preferences
        }

        test("serves the stock intervals before the first preference read lands") {
            val holder = SkipIntervalsHolder(preferences(MutableStateFlow(45), MutableStateFlow(20)))

            // Nothing has collected yet — a car can connect within milliseconds of onCreate, and
            // a zero-second skip is a worse answer than the stock one.
            holder.forwardSec shouldBe PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC
            holder.backwardSec shouldBe PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC
        }

        test("follows the synced preference, in seconds and in milliseconds") {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val holder = SkipIntervalsHolder(preferences(MutableStateFlow(45), MutableStateFlow(20)))

                holder.follow(scope)

                holder.forwardSec shouldBe 45
                holder.backwardSec shouldBe 20
                holder.forwardMs shouldBe 45_000L
                holder.backwardMs shouldBe 20_000L
            } finally {
                scope.cancel()
            }
        }

        test("a change made in Settings lands without the service restarting") {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                val forward = MutableStateFlow(45)
                val backward = MutableStateFlow(20)
                val holder = SkipIntervalsHolder(preferences(forward, backward))

                holder.follow(scope)
                forward.value = 90
                backward.value = 5

                holder.forwardSec shouldBe 90
                holder.backwardSec shouldBe 5
            } finally {
                scope.cancel()
            }
        }
    })
