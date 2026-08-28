package com.calypsan.listenup.web.features.settings

import io.kotest.matchers.nulls.shouldNotBeNull
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

/** A speed off the ladder's default rung, so a control that ignores its input cannot pass. */
private const val CHOSEN_SPEED = 1.5f

private const val QUARTER_SPEED = 1.25f

/** A skip interval that is neither of the two defaults (10 back, 30 forward). */
private const val CHOSEN_SKIP_SEC = 15

/** Index of each `<select>` in render order: theme, speed, skip back, skip forward. */
private const val THEME_SELECT = 0

private const val SPEED_SELECT = 1

private const val SKIP_BACK_SELECT = 2

private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

@Composable
private fun page(
    state: SettingsUiState = SettingsUiState(isLoading = false),
    onThemeMode: (ThemeMode) -> Unit = {},
    onDefaultSpeed: (Float) -> Unit = {},
    onSkipForward: (Int) -> Unit = {},
    onSkipBackward: (Int) -> Unit = {},
    onAutoRewind: (Boolean) -> Unit = {},
    onIgnoreTitleArticles: (Boolean) -> Unit = {},
    onHideSingleBookSeries: (Boolean) -> Unit = {},
    onOpenDevices: () -> Unit = {},
) {
    SettingsPage(
        state = state,
        onThemeMode = onThemeMode,
        onDefaultSpeed = onDefaultSpeed,
        onSkipForward = onSkipForward,
        onSkipBackward = onSkipBackward,
        onAutoRewind = onAutoRewind,
        onIgnoreTitleArticles = onIgnoreTitleArticles,
        onHideSingleBookSeries = onHideSingleBookSeries,
        onOpenDevices = onOpenDevices,
    )
}

/** Pick an option by value and fire the change the browser would. */
private fun select(
    host: HTMLElement,
    index: Int,
    value: String,
) {
    val el = host.querySelectorAll("select").item(index) as HTMLSelectElement
    el.value = value
    el.dispatchEvent(Event("change", eventInit()))
}

private fun eventInit(): dynamic {
    val init: dynamic = js("({})")
    init.bubbles = true
    return init
}

/**
 * Settings' contract, and mostly its omissions.
 *
 * The page's job is as much about what it refuses to render as what it shows: five of the shared
 * ViewModel's twelve controls cannot be honoured in a browser, and a toggle that silently does
 * nothing is the failure this codebase works hardest to avoid.
 */
class SettingsPageTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("the four sections a browser can keep are all present") {
            val host = mount { page() }

            val text = host.textContent.orEmpty()
            text shouldContain "Appearance"
            text shouldContain "Playback"
            text shouldContain "Library"
            text shouldContain "About"
        }

        test("nothing a browser cannot honour is offered") {
            // Each of these has a real control on Android. Rendering one here — even disabled —
            // would promise something the browser has no way to deliver.
            val host = mount { page() }

            val text = host.textContent.orEmpty()
            text shouldNotContain "Dynamic"
            text shouldNotContain "Wi-Fi"
            text shouldNotContain "Haptic"
            text shouldNotContain "boost"
            text shouldNotContain "Sleep"
        }

        test("each section says whether a setting travels or stays") {
            // The synced/local split is invisible until it surprises you — changing a phone setting
            // and watching this browser ignore it, or the reverse.
            val host = mount { page() }

            host.textContent.orEmpty() shouldContain "Follows you to your other devices."
            host.textContent.orEmpty() shouldContain "Kept on this browser."
        }

        test("choosing a theme reports the mode, not the label") {
            var chosen: ThemeMode? = null
            val host = mount { page(onThemeMode = { chosen = it }) }

            select(host, index = THEME_SELECT, value = "DARK")

            chosen shouldBe ThemeMode.DARK
        }

        test("a stored theme value this build does not know falls back to following the system") {
            // Defence against a value that arrived some other way — an older build, a hand-edited
            // store. Following the system is the answer least likely to surprise.
            themeModeOf("BANANA") shouldBe ThemeMode.SYSTEM
            themeModeOf("DARK") shouldBe ThemeMode.DARK
        }

        test("the speed picker round-trips through the value it renders") {
            // The option's value is parsed straight back to a Float. If the two ever disagree the
            // control silently stops reporting anything.
            var speed: Float? = null
            val host = mount { page(onDefaultSpeed = { speed = it }) }

            select(host, index = SPEED_SELECT, value = speedKey(CHOSEN_SPEED))

            speed shouldBe CHOSEN_SPEED
        }

        test("skip intervals report seconds, not labels") {
            var back: Int? = null
            val host = mount { page(onSkipBackward = { back = it }) }

            select(host, index = SKIP_BACK_SELECT, value = CHOSEN_SKIP_SEC.toString())

            back shouldBe CHOSEN_SKIP_SEC
        }

        test("the current values are the ones selected") {
            val host =
                mount {
                    page(
                        state =
                            SettingsUiState(
                                isLoading = false,
                                themeMode = ThemeMode.DARK,
                                defaultSkipBackwardSec = CHOSEN_SKIP_SEC,
                            ),
                    )
                }

            (host.querySelectorAll("select").item(THEME_SELECT) as HTMLSelectElement).value shouldBe "DARK"
            (host.querySelectorAll("select").item(SKIP_BACK_SELECT) as HTMLSelectElement).value shouldBe CHOSEN_SKIP_SEC.toString()
        }

        test("a speed label drops the noise but never the meaning") {
            formatSpeedLabel(1f) shouldBe "1"
            formatSpeedLabel(CHOSEN_SPEED) shouldBe "1.5"
            formatSpeedLabel(QUARTER_SPEED) shouldBe "1.25"
        }

        test("a server with no version reported says nothing rather than saying null") {
            val host =
                mount { page(state = SettingsUiState(isLoading = false, serverUrl = "https://x", serverVersion = null)) }

            host.textContent.orEmpty() shouldContain "https://x"
            host.textContent.orEmpty() shouldNotContain "null"
        }

        test("while loading it shows a shape, not a form full of defaults") {
            // Every field has a default, so a form rendered before the real values arrive would show
            // confident wrong answers and invite someone to "correct" one.
            val host = mount { page(state = SettingsUiState(isLoading = true)) }

            host.querySelectorAll("select").length shouldBe 0
            host.querySelector(".set-skel").shouldNotBeNull()
        }
    })
