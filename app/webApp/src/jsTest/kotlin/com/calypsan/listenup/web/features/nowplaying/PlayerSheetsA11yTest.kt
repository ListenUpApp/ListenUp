package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.contrast
import com.calypsan.listenup.web.rgb
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

private const val WIDE = 1440

private const val WIDE_HEIGHT = 900

private const val PHONE_HEIGHT = 844

private val CHAPTER =
    PlayerChapter(5, 6, 75, "Bridge Four", isGenericTitle = false, isWholeBook = false, startMs = 0, durationMs = 1_235_000)

private val RUNNING = SleepTimerState.Active(SleepTimerMode.Duration(30), 1_421_000, 1_800_000, 0)

/** The five panels the sweep walks, each drawn as it is in the app. */
private enum class Panel(
    val panelClass: String,
) {
    Speed("speed-dlg"),
    SleepIdle("sleep-dlg"),
    SleepRunning("sleep-dlg"),
    Boost("boost-dlg"),
    Chapters("chap-dlg"),
}

@Composable
private fun DrawPanel(
    panel: Panel,
    placement: PanelPlacement,
) {
    when (panel) {
        Panel.Speed -> {
            SpeedPicker(true, 1.5f, 1f, 116_640_000, placement, {}, {}, {}, {})
        }

        Panel.SleepIdle, Panel.SleepRunning -> {
            val state = if (panel == Panel.SleepRunning) RUNNING else SleepTimerState.Inactive
            SleepTimerPicker(
                open = true,
                state = state,
                chapter = CHAPTER,
                chapterRemainingMs = 480_000,
                countdownMs = (state as? SleepTimerState.Active)?.remainingMs,
                meterFraction = 0.79f,
                nowMs = 1_761_000_000_000,
                placement = placement,
                onSet = {},
                onCancel = {},
                onExtend = {},
                onKeepListening = {},
                onDismiss = {},
            )
        }

        Panel.Boost -> {
            BoostPicker(true, 6f, 0f, unavailable = true, onSet = {}, onReset = {}, placement = placement, onDismiss = {})
        }

        Panel.Chapters -> {
            ChapterPicker(true, chapterMarks(count = 20, currentIndex = 5), 480_000, 163_800_000, placement, {}, {})
        }
    }
}

/**
 * The player panels' accessibility, caught up front (PR 3's misses): every control a 44px target at a phone's width and
 * a desktop's, no sideways scroll and nothing out of reach at 200% text, and every ink at 4.5:1 (3:1 for the meter) in
 * both themes.
 */
class PlayerSheetsA11yTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        /** [panel] open as a sheet in a phone frame. */
        suspend fun sheet(
            panel: Panel,
            height: Int = PHONE_HEIGHT,
        ): ViewportFrame {
            val frame = frames.mount(PHONE, height) { DrawPanel(panel, PanelPlacement.Sheet) }
            awaitFrame()
            return frame
        }

        /** [panel] open as a popover from a button in a desktop frame. */
        suspend fun popover(panel: Panel): ViewportFrame {
            var placement by mutableStateOf<PanelPlacement?>(null)
            val frame =
                frames.mount(WIDE, WIDE_HEIGHT) {
                    Div(attrs = { style { property("padding", "200px 400px") } }) {
                        Button(attrs = { classes("opener") }) { Text("Open") }
                    }
                    placement?.let { DrawPanel(panel, it) }
                }
            placement = PanelPlacement.Anchored(frame.find(".opener"))
            awaitFrame()
            return frame
        }

        fun ViewportFrame.panelBox(panel: Panel): HTMLElement = find(".${panel.panelClass}")

        fun ViewportFrame.controls(panel: Panel): List<HTMLElement> =
            panelBox(panel).querySelectorAll("button, a, input").let { list ->
                (0 until list.length).map { list.item(it) as HTMLElement }
            }

        fun ViewportFrame.dark(on: Boolean) {
            val root = host.ownerDocument!!.documentElement!!
            if (on) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
        }

        fun ViewportFrame.colour(
            selector: String,
            property: String,
        ): List<Double> = rgb(css(find(selector), property))

        Panel.entries.forEach { panel ->
            listOf("390 sheet" to true, "1440 popover" to false).forEach { (where, isSheet) ->
                test("targets: every control in $panel is at least 44px tall ($where)") {
                    val frame = if (isSheet) sheet(panel) else popover(panel)
                    frame.controls(panel).forEach { control ->
                        withClue("$panel ${control.className} '${control.textContent}'") {
                            frame.rect(control).height shouldBeGreaterThanOrEqual 44.0
                        }
                    }
                    if (panel == Panel.Speed) {
                        listOf("Slower by 0.05", "Faster by 0.05").forEach { label ->
                            frame.rect(frame.find("button[aria-label='$label']")).width shouldBeGreaterThanOrEqual 44.0
                        }
                    }
                }
            }

            test("200% text: $panel as a sheet scrolls only up and down, and its last control can be reached") {
                val frame = sheet(panel)
                val root = frame.host.ownerDocument!!.documentElement!!.unsafeCast<HTMLElement>()
                root.style.fontSize = "32px"
                awaitFrame()
                try {
                    val box = frame.panelBox(panel)
                    withClue("$panel sideways overflow") { box.scrollWidth shouldBeLessThanOrEqual box.clientWidth }
                    if (box.scrollHeight > box.clientHeight) frame.css(box, "overflow-y") shouldBe "auto"
                    val last = frame.controls(panel).last()
                    last.asDynamic().scrollIntoView(js("({ block: 'nearest' })"))
                    awaitFrame()
                    val inner = frame.rect(last)
                    val outer = frame.rect(box)
                    withClue("$panel last control within the panel") {
                        inner.top shouldBeGreaterThanOrEqual outer.top - 0.5
                        inner.bottom shouldBeLessThanOrEqual outer.bottom + 0.5
                        inner.left shouldBeGreaterThanOrEqual outer.left - 0.5
                        inner.right shouldBeLessThanOrEqual outer.right + 0.5
                    }
                } finally {
                    root.style.removeProperty("font-size")
                }
            }
        }

        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"

            test("$theme: the panels' quiet ink reads at 4.5:1 on the panel") {
                listOf(
                    Panel.Speed to ".speed-left",
                    Panel.Speed to ".panel-note",
                    Panel.SleepRunning to ".sleep-pauses",
                    Panel.Chapters to ".chap-row:not(.on) .chap-dur",
                    Panel.Chapters to ".chap-sum",
                ).forEach { (panel, selector) ->
                    val frame = sheet(panel)
                    frame.dark(dark)
                    val ratio =
                        contrast(frame.colour(selector, "color"), frame.colour(".${panel.panelClass}", "background-color"))
                    withClue("$selector ($theme): $ratio") { ratio shouldBeGreaterThanOrEqual 4.5 }
                    frames.disposeAll()
                }
            }

            test("$theme: the gold reads — End of chapter, the fade card's Keep listening, and the meter at 3:1") {
                val idle = sheet(Panel.SleepIdle)
                idle.dark(dark)
                val eoc = contrast(idle.colour(".sleep-eoc", "color"), idle.colour(".sleep-eoc", "background-color"))
                withClue("sleep ink on its container ($theme): $eoc") { eoc shouldBeGreaterThanOrEqual 4.5 }
                frames.disposeAll()

                val running = sheet(Panel.SleepRunning)
                running.dark(dark)
                val meter =
                    contrast(running.colour(".sleep-meter-fill", "background-color"), running.colour(".sleep-meter", "background-color"))
                withClue("the meter on its track ($theme): $meter") { meter shouldBeGreaterThanOrEqual 3.0 }
                frames.disposeAll()

                val fading =
                    frames.mount(PHONE, PHONE_HEIGHT) {
                        PlayerStateCard(
                            card = PlayerCard.SleepFading,
                            onAction = {},
                            onRate = {},
                        )
                    }
                fading.dark(dark)
                val card = contrast(fading.colour(".np-card.sleep", "color"), fading.colour(".np-card.sleep", "background-color"))
                val keep = contrast(fading.colour(".np-keep", "color"), fading.colour(".np-keep", "background-color"))
                withClue("the card's ink ($theme): $card") { card shouldBeGreaterThanOrEqual 4.5 }
                withClue("Keep listening ($theme): $keep") { keep shouldBeGreaterThanOrEqual 4.5 }
            }

            test("$theme: the current chapter's ink reads on coral, and the chosen preset's too") {
                val chapters = sheet(Panel.Chapters)
                chapters.dark(dark)
                val row = contrast(chapters.colour(".chap-row.on", "color"), chapters.colour(".chap-row.on", "background-color"))
                withClue("current row ($theme): $row") { row shouldBeGreaterThanOrEqual 4.5 }
                frames.disposeAll()

                val speed = sheet(Panel.Speed)
                speed.dark(dark)
                val preset = contrast(speed.colour(".speed-opt.is-on", "color"), speed.colour(".speed-opt.is-on", "background-color"))
                withClue("chosen preset ($theme): $preset") { preset shouldBeGreaterThanOrEqual 4.5 }
            }

            test("$theme: the page's header ink still reads on every wash") {
                (listOf<String?>(null) + if (dark) DARK_COVER_WASHES else LIGHT_COVER_WASHES).forEach { wash ->
                    val frame =
                        frames.mount(480, 300) {
                            Div(attrs = {
                                classes("np-page")
                                if (wash != null) {
                                    classes("cover-themed")
                                    style {
                                        property("--cover-wash-l", wash)
                                        property("--cover-wash-d", wash)
                                    }
                                }
                            }) { Button(attrs = { classes("np-collapse") }) { Text("Back") } }
                        }
                    frame.dark(dark)
                    val ratio = contrast(frame.colour(".np-collapse", "color"), frame.colour(".np-page", "background-color"))
                    withClue("header ink on ${wash ?: "the neutral"} ($theme): $ratio") { ratio shouldBeGreaterThanOrEqual 4.5 }
                    frames.disposeAll()
                }
            }
        }
    })
