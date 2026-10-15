package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.animatedProperties
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.durationOf
import com.calypsan.listenup.web.motion.MotionToken
import com.calypsan.listenup.web.motion.reducedMotionOverride
import com.calypsan.listenup.web.motions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.w3c.dom.HTMLElement

/**
 * A book switch on the player bar reads as deliberate: the new book rises into the bar, which itself never leaves
 * (Simon, 2026-10-14). The motion is keyed on the book, so nothing else the bar shows — a pause, a stall, a tick —
 * moves it, and under reduced motion the new book simply appears.
 */
class BookSwitchMotionTest :
    FunSpec({
        val mounts = MountRegistry()
        val state = mutableStateOf(loadedPlayerState(title = "The Way of Kings", bookId = "kings"))

        afterTest {
            mounts.disposeAll()
            state.value = loadedPlayerState(title = "The Way of Kings", bookId = "kings")
            reducedMotionOverride = null
        }

        fun mount(): HTMLElement =
            mounts.mount {
                TransportBar(
                    state = state.value,
                    progress = playerProgress(),
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSetSpeed = {},
                )
            }

        test("the first book arrives without motion, and the bar names it") {
            val host = mount()
            awaitFrame()

            host.querySelector(".tport")!!.getAttribute("data-book") shouldBe "kings"
            (host.querySelector(".tport-body") as HTMLElement).motions().size shouldBe 0
        }

        test("a new book rises into the bar, and the bar itself never leaves") {
            val host = mount()
            awaitFrame()
            val bar = host.querySelector(".tport") as HTMLElement

            state.value = loadedPlayerState(title = "Words of Radiance", bookId = "radiance")
            awaitFrame()

            // The same node: the bar was never taken down and put back, so there is no frame without it.
            host.querySelector(".tport") as HTMLElement shouldBeSameInstanceAs bar
            bar.getAttribute("data-book") shouldBe "radiance"
            host.querySelector(".tport-t")!!.textContent shouldBe "Words of Radiance"
            val arrival = (host.querySelector(".tport-body") as HTMLElement).motions().single()
            animatedProperties(arrival) shouldBe setOf("opacity", "transform")
            durationOf(arrival) shouldBe MotionToken.MOVE.millis
        }

        test("the same book changing phase does not move") {
            val host = mount()
            awaitFrame()

            state.value = loadedPlayerState(title = "The Way of Kings", bookId = "kings", phase = PlayerPhase.Paused)
            awaitFrame()

            (host.querySelector(".tport-body") as HTMLElement).motions().size shouldBe 0
        }

        test("under reduced motion a new book simply appears") {
            reducedMotionOverride = true
            val host = mount()
            awaitFrame()

            state.value = loadedPlayerState(title = "Words of Radiance", bookId = "radiance")
            awaitFrame()

            host.querySelector(".tport-t")!!.textContent shouldBe "Words of Radiance"
            (host.querySelector(".tport-body") as HTMLElement).motions().size shouldBe 0
        }
    })
