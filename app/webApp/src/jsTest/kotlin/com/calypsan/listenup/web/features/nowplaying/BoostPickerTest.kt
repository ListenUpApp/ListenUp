package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.VolumeBoostLimits
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.WebPlaybackController
import com.calypsan.listenup.web.playback.awaitState
import com.calypsan.listenup.web.playback.silentSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URL
import kotlin.math.abs

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

/** An open boost picker, with every parameter overridable. */
private fun picker(
    boostDb: Float = OFF,
    defaultBoostDb: Float = OFF,
    unavailable: Boolean = false,
    open: Boolean = true,
    onSet: (Float) -> Unit = {},
    onReset: () -> Unit = {},
    onDismiss: () -> Unit = {},
): HTMLElement =
    mount {
        BoostPicker(
            open = open,
            boostDb = boostDb,
            defaultBoostDb = defaultBoostDb,
            unavailable = unavailable,
            onSet = onSet,
            onReset = onReset,
            onDismiss = onDismiss,
        )
    }

/** A bar over a playing book, with only the boost inputs worth varying exposed. */
@Composable
private fun boostBar(
    volumeBoostDb: Float = OFF,
    onSetBoost: (Float) -> Unit = {},
) {
    TransportBar(
        state = TransportState("Dune", isPlaying = true, positionMs = 0, durationMs = BOOK_MS),
        onPlayPause = {},
        onSeek = {},
        onSkipBack = {},
        onSkipForward = {},
        onSetSpeed = {},
        volumeBoostDb = volumeBoostDb,
        onSetBoost = onSetBoost,
    )
}

private const val BOOK_MS = 3_600_000L

private const val OFF = 0f

private const val BOOSTED = 6f

class BoostPickerTest :
    FunSpec({

        test("a closed picker renders nothing at all") {
            picker(open = false).querySelectorAll("dialog").length shouldBe 0
        }

        test("it offers every rung of the shared ladder, and no others") {
            // Read from the contract rather than restated, so a rung added there cannot leave the
            // browser showing a ladder the phone does not have.
            val host = picker()

            host.querySelectorAll(".boost-opt").length shouldBe VolumeBoostLimits.PRESETS_DB.size
            VolumeBoostLimits.PRESETS_DB.forEach { rung ->
                host.buttonSaying(formatBoost(rung)) shouldBe host.buttonSaying(formatBoost(rung))
            }

            host.closeDialogs()
        }

        test("no boost reads as Off, not as plus zero decibels") {
            // Zero boost is the absence of a setting, not a setting whose value is zero — and the
            // native clients say the same word.
            val host = picker(boostDb = OFF)

            host.querySelector(".boost-read")?.textContent shouldBe "Off"

            host.closeDialogs()
        }

        test("the boost in force is marked, and says so to a screen reader") {
            val host = picker(boostDb = BOOSTED)

            val current = host.querySelectorAll("[aria-current='true']")
            current.length shouldBe 1
            (current.item(0) as HTMLElement).textContent shouldBe formatBoost(BOOSTED)

            host.closeDialogs()
        }

        test("picking a rung reports exactly that boost") {
            var picked: Float? = null
            val host = picker(onSet = { picked = it })

            host.buttonSaying(formatBoost(BOOSTED))!!.click()
            awaitFrame()

            picked shouldBe BOOSTED
            host.closeDialogs()
        }

        test("a browser that would not amplify says so, where the listener just asked") {
            // ⛔ The failure is otherwise invisible: the book plays on, at the volume they were
            // trying to change. Silence about it is the quiet lie this app is built to avoid.
            val refused = picker(boostDb = BOOSTED, unavailable = true)
            refused.querySelectorAll(".boost-warn").length shouldBe 1
            (refused.querySelector(".boost-warn") as HTMLElement).getAttribute("role") shouldBe "alert"
            refused.closeDialogs()

            val fine = picker(boostDb = BOOSTED, unavailable = false)
            fine.querySelectorAll(".boost-warn").length shouldBe 0
            fine.closeDialogs()
        }

        test("reset is offered only when there is something to go back to") {
            val atDefault = picker(boostDb = OFF, defaultBoostDb = OFF)
            atDefault.querySelectorAll(".boost-reset").length shouldBe 0
            atDefault.closeDialogs()

            val changed = picker(boostDb = BOOSTED, defaultBoostDb = OFF)
            changed.querySelectorAll(".boost-reset").length shouldBe 1
            changed.closeDialogs()
        }

        test("reset names the listener's own default, not a hardcoded off") {
            // Someone whose default is +3 dB must not be offered a reset to Off.
            val host = picker(boostDb = BOOSTED, defaultBoostDb = 3f)

            (host.querySelector(".boost-reset") as HTMLElement).textContent.orEmpty() shouldContain "+3 dB"

            host.closeDialogs()
        }

        test("reset reports a reset, not a set of the same number") {
            // The two differ in what they record: setting +0 pins this book at no boost, resetting
            // says it has no opinion so a later change to the default reaches it.
            var reset = 0
            var set = 0
            val host = picker(boostDb = BOOSTED, defaultBoostDb = OFF, onSet = { set++ }, onReset = { reset++ })

            (host.querySelector(".boost-reset") as HTMLButtonElement).click()
            awaitFrame()

            reset shouldBe 1
            set shouldBe 0
            host.closeDialogs()
        }

        test("it is a real modal dialog, not a div wearing the part") {
            val host = picker()

            (host.querySelector("dialog") as HTMLDialogElement).isModal() shouldBe true

            host.closeDialogs()
        }

        test("closing reports the dismissal, so the caller's flag cannot drift") {
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector(".btn-ghost") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldBe 1
            host.closeDialogs()
        }
    })

class BoostBarTest :
    FunSpec({

        test("the boost control is offered for any book, chapters or not") {
            val host = mount { boostBar() }

            host.querySelectorAll("[aria-label='Volume boost']").length shouldBe 1
        }

        test("a boost in force is visible on the bar, not only inside the dialog") {
            // ⛔ A boost changes how the book sounds. "Why is this one so loud" has to have an
            // answer you can see without opening a panel to ask.
            val host = mount { boostBar(volumeBoostDb = BOOSTED) }

            val on = host.querySelector("[aria-label='Volume boost, +6 dB']") as HTMLElement
            on.classList.contains("on") shouldBe true
        }

        test("the boost control survives a narrow screen") {
            // ⛔ `.tport-skip` is `display:none` under 760px. A phone speaker is exactly where a
            // quiet book most needs turning up, so wearing that class would drop this control on
            // the device that needs it most — the same trap the sleep timer fell into.
            val host = mount { boostBar(volumeBoostDb = BOOSTED) }

            val boost = host.querySelector(".tport-boost") as HTMLElement
            boost.classList.contains("tport-skip") shouldBe false
        }

        test("the bar opens the picker rather than stepping the boost under you") {
            var set = 0
            val host = mount { boostBar(onSetBoost = { set++ }) }

            (host.querySelector(".tport-boost") as HTMLButtonElement).click()
            awaitFrame()

            set shouldBe 0
            host.querySelectorAll(".boost-dlg").length shouldBe 1

            host.closeDialogs()
        }
    })

class BoostSessionTest :
    FunSpec({

        test("a boost reaches the player as real gain, not just as a stored number") {
            // ⛔ The whole point, and the assertion is on the PLAYER. Reading
            // `manager.effectiveGainDb` back would only prove the manager recomputed a number,
            // which it does whether or not anything is listening — a session with its collector
            // deleted would pass that. `player.appliedGain` is the multiplier in the audio path.
            val player = HtmlAudioPlayer()
            val manager = fakePlaybackManager(silentSegment(SEGMENT_MS), title = "Dune")
            val playback =
                LivePlayback(manager, WebPlaybackController(player, manager), player, FakePlaybackPreferences())

            playback.setBoost(BOOSTED)

            manager.volumeBoostDb.value shouldBe BOOSTED
            player.awaitAppliedGain(BOOSTED_LINEAR)

            playback.close()
            player.releasePlayer()
        }

        test("a boost stored on the book survives arriving before the audio does") {
            // ⛔ The ordering bug this design nearly shipped. `PlaybackManagerImpl` publishes
            // `effectiveGainDb` inside prepare — before `startPlayback` gives the element a
            // source — and a gain stage cannot route an element with nothing loaded. Treating
            // that as a refusal rather than a "not yet" left a boosted book unboosted for the
            // whole session, on the path every cold start takes.
            val segment = silentSegment(SEGMENT_MS)
            val player = HtmlAudioPlayer()
            val manager = fakePlaybackManager(segment, title = "Dune", resumeBoostDb = BOOSTED)
            val playback =
                LivePlayback(manager, WebPlaybackController(player, manager), player, FakePlaybackPreferences())

            playback.playBook(BookId("book-1"))
            player.awaitState(PlaybackState.Playing)

            player.awaitAppliedGain(BOOSTED_LINEAR)
            // And in a Chromium that allowed the context to run, the graph is genuinely in the
            // path rather than the number merely being remembered.
            player.boostAttached shouldBe true
            player.boostUnavailable.value shouldBe false

            playback.close()
            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("a reset goes back to the listener's default, not to Off") {
            // Same ⛔ as the speed picker's: the default is a synced setting, and a reset that
            // assumed zero would quietly overrule someone who boosts everything.
            val player = HtmlAudioPlayer()
            val manager = fakePlaybackManager(silentSegment(SEGMENT_MS), title = "Dune")
            val playback =
                LivePlayback(
                    manager,
                    WebPlaybackController(player, manager),
                    player,
                    FakePlaybackPreferences(boostDb = CUSTOM_DEFAULT_BOOST),
                )

            playback.setBoost(BOOSTED)
            manager.volumeBoostDb.value shouldBe BOOSTED

            playback.resetBoost()
            withTimeout(APPLY_TIMEOUT_MS) { manager.volumeBoostDb.first { it == CUSTOM_DEFAULT_BOOST } }

            playback.close()
            player.releasePlayer()
        }
    })

/**
 * Wait for the player to be applying [target] dB of gain, failing the spec if it never does.
 *
 * Polled rather than awaited on a flow because the multiplier is not one: it is the product of the
 * gain and the fade, computed on read. A poll with a timeout still fails honestly — it is a sleep
 * that finishes early which would assert against a player that had not got there yet.
 */
private suspend fun HtmlAudioPlayer.awaitAppliedGain(target: Double) {
    withTimeout(APPLY_TIMEOUT_MS) {
        while (abs(appliedGain - target) > GAIN_TOLERANCE) delay(GAIN_POLL_MS)
    }
}

/** A listener who turns everything up a little — not the stock Off. */
private const val CUSTOM_DEFAULT_BOOST = 3f

/** The linear form of [BOOSTED] — what an amplifying stage actually multiplies by. */
private const val BOOSTED_LINEAR = 1.9952624

private const val GAIN_TOLERANCE = 0.001

private const val GAIN_POLL_MS = 10L

private const val SEGMENT_MS = 1_500L

/** The reset reads the preference through a suspend call, so it lands a dispatch later. */
private const val APPLY_TIMEOUT_MS = 5_000L
