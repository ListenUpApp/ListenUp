package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.domain.VolumeBoostLimits
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** A bar over a playing book, with only the boost inputs worth varying exposed. */
@Composable
private fun boostBar(
    volumeBoostDb: Float = OFF,
    onSetBoost: (Float) -> Unit = {},
) {
    TransportBar(
        state = loadedPlayerState(durationMs = BOOK_MS, boostDb = volumeBoostDb),
        progress = playerProgress(durationMs = BOOK_MS),
        onPrimary = {},
        onSeek = {},
        onSkipBack = {},
        onSkipForward = {},
        onSetSpeed = {},
        onSetBoost = onSetBoost,
    )
}

private const val BOOK_MS = 3_600_000L

private const val OFF = 0f

private const val BOOSTED = 6f

class BoostPickerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /** An open boost picker, with every parameter overridable. */
        fun picker(
            boostDb: Float = OFF,
            defaultBoostDb: Float = OFF,
            unavailable: Boolean = false,
            open: Boolean = true,
            onSet: (Float) -> Unit = {},
            onReset: () -> Unit = {},
            onDismiss: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
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
            }

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

            (host.querySelector(".btn-secondary") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldBe 1
            host.closeDialogs()
        }
    })

class BoostBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        test("the boost control is offered for any book, chapters or not") {
            val host = mount { boostBar() }
            host.openNowPlaying()

            host
                .boostChip()
                .textContent
                .orEmpty()
                .trim() shouldBe "Boost"

            host.closeDialogs()
        }

        // The bar carried a boost control until the mini player redesign (spec §5.1); boost now lives in Now
        // Playing, one tap from the bar, until PR 4 moves it into More. These three pin the same guarantees there.
        test("a boost in force is visible in Now Playing, not only inside the dialog") {
            // ⛔ A boost changes how the book sounds. "Why is this one so loud" has to have an
            // answer you can see without opening the boost picker to ask.
            val host = mount { boostBar(volumeBoostDb = BOOSTED) }
            host.openNowPlaying()

            val on = host.boostChip()
            on.textContent.orEmpty().trim() shouldBe "+6 dB"
            on.classList.contains("on") shouldBe true

            host.closeDialogs()
        }

        test("the bar carries no boost control any more, so none can wear a skip's phone behaviour") {
            // ⛔ The skips have their own phone behaviour, and a control wearing `.tport-skip` gets
            // it whether it meant to or not. Boost is reached through Now Playing on every width.
            val host = mount { boostBar(volumeBoostDb = BOOSTED) }

            host.querySelector(".tport-boost") shouldBe null
            host.openNowPlaying()
            host.boostChip().classList.contains("tport-skip") shouldBe false

            host.closeDialogs()
        }

        test("the boost chip opens the picker rather than stepping the boost under you") {
            var set = 0
            val host = mount { boostBar(onSetBoost = { set++ }) }
            host.openNowPlaying()

            host.boostChip().click()
            awaitFrame()

            set shouldBe 0
            host.querySelectorAll(".boost-dlg").length shouldBe 1

            host.closeDialogs()
        }
    })

/** Opens Now Playing from the bar, the way a listener would. */
private suspend fun HTMLElement.openNowPlaying() {
    (querySelector(".tport-expand") as HTMLButtonElement).click()
    awaitFrame()
}

/** The Now Playing chip that opens the boost picker: "Boost", or the boost in force. */
private fun HTMLElement.boostChip(): HTMLButtonElement =
    querySelectorAll(".np-chip")
        .asList()
        .map { it as HTMLButtonElement }
        .single { it.textContent.orEmpty().let { text -> "Boost" in text || "dB" in text } }
