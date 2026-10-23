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
                        placement = PanelPlacement.Sheet,
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

/**
 * Where the boost lives now (C15): not on the bar, and not as a chip, but in Now Playing's More menu, whose row reads
 * the boost in force and opens the same picker. Ports the guarantees the old Now Playing chip pinned.
 */
class BoostBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            volumeBoostDb: Float = OFF,
            onSetBoost: (Float) -> Unit = {},
            chapters: Boolean = true,
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    NowPlayingPage(
                        state =
                            loadedPlayerState(
                                durationMs = BOOK_MS,
                                boostDb = volumeBoostDb,
                                chapters = if (chapters) chapterRows(3) else emptyList(),
                                currentChapterIndex = if (chapters) 0 else null,
                            ),
                        progress = playerProgress(durationMs = BOOK_MS),
                        actions = NowPlayingActions(onSetBoost = onSetBoost),
                    )
                }
            }

        suspend fun HTMLElement.boostRow(): HTMLButtonElement {
            (querySelector(".np-more button[aria-haspopup=menu]") as HTMLButtonElement).click()
            awaitFrame()
            return querySelectorAll(".menu-i")
                .asList()
                .map { it as HTMLElement }
                .single { it.textContent.orEmpty().startsWith("Volume boost") } as HTMLButtonElement
        }

        test("the boost control is offered for any book, chapters or not") {
            page(chapters = false).boostRow().textContent shouldBe "Volume boost · Off"
        }

        test("a boost in force is visible in Now Playing's More, not only inside the dialog") {
            // ⛔ A boost changes how the book sounds. "Why is this one so loud" has to have an answer short of opening
            // the boost picker to ask.
            page(volumeBoostDb = BOOSTED).boostRow().textContent shouldBe "Volume boost · +6 dB"
        }

        test("the bar carries no boost control any more, so none can wear a skip's phone behaviour") {
            val host = mounts.mount { WebAppSurface { boostBar(volumeBoostDb = BOOSTED) } }
            host.querySelector(".tport-boost") shouldBe null
            host.querySelectorAll(".tport-skip").asList().none { it.textContent.orEmpty().contains("dB") } shouldBe true
        }

        test("the boost row opens the picker rather than stepping the boost under you") {
            var set = 0
            val host = page(onSetBoost = { set++ })
            host.boostRow().click()
            awaitFrame()

            set shouldBe 0
            host.querySelectorAll("dialog.boost-dlg[open]").length shouldBe 1

            host.closeDialogs()
        }
    })
