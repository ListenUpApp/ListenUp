package com.calypsan.listenup.web

import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback
import com.calypsan.listenup.web.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.web.features.nowplaying.playerProgress
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow

private const val BOOK_MS = 3_600_000L

private const val TICK_MS = 250L

/**
 * The playback tick, and what it is allowed to touch.
 *
 * [PlayerProgress] carries the position and moves several times a second while a book plays.
 * What this pins: the tick moves the bar's clock, and does not re-run the shell's content lambda
 * — the account menu, the route switch, the dead-letter notice and the palette host, all to move
 * one scrubber thumb.
 */
class PlaybackTickTest :
    FunSpec({
        test("the playback tick moves the transport bar without recomposing the shell's content") {
            val session =
                fixedPlayback(
                    state = loadedPlayerState(durationMs = BOOK_MS),
                    progress = playerProgress(positionMs = 0, durationMs = BOOK_MS),
                )()
            val progress = session.progress as MutableStateFlow<PlayerProgress>
            var shellCompositions = 0
            val (host, router, composition) =
                mountAt(
                    path = "/library",
                    openPlayback = { session },
                    compositionProbe = { if (it == SHELL_CONTENT_PROBE) shellCompositions++ },
                )
            try {
                awaitPresent(host, ".tport")
                repeat(3) { awaitFrame() }
                val settled = shellCompositions
                withClue("the probe is wired: the shell composed at least once") { settled shouldBeGreaterThan 0 }

                (1..8).forEach { tick ->
                    progress.value = playerProgress(positionMs = tick * TICK_MS * 4, durationMs = BOOK_MS)
                    awaitFrame()
                }

                host.querySelector(".tport-time")!!.textContent shouldBe "0:08"
                withClue("shell content recompositions across eight ticks") { shellCompositions shouldBe settled }
            } finally {
                composition.dispose()
                router.dispose()
                host.remove()
            }
        }
    })
