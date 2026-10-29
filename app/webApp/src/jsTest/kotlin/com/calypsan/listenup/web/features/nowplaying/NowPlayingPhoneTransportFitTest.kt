package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.design.LocalCoverThemes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import org.w3c.dom.asList

/**
 * The phone Now Playing's transport (previous, back N, play, forward N, next) fits a narrow phone: nothing scrolls
 * sideways, every control stays inside the window, and each keeps a 44px target. The 390px+ layout is unchanged.
 */
class NowPlayingPhoneTransportFitTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(SMALL_PHONE, NARROW_PHONE).forEach { width ->
            test("at ${width}px the five transport controls fit the window with 44px targets, and nothing scrolls sideways") {
                val frame =
                    frames.mount(width = width, height = PHONE_HEIGHT) {
                        CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                            NowPlayingPage(
                                state = wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5),
                                progress = PlayerProgress.Zero,
                                actions = NowPlayingActions(),
                            )
                        }
                    }
                val root = frame.window.document.documentElement!!
                val edge = root.clientWidth.toDouble()
                val controls = frame.findAll(".np-mob .np-transport > *")
                controls.size shouldBe 5
                controls.forEach { control ->
                    val box = frame.rect(control)
                    withClue("${control.getAttribute("class")} at ${box.left}..${box.right} in a ${edge}px window") {
                        (box.left >= 0.0 && box.right <= edge + 0.5) shouldBe true
                        (box.width >= 44.0 && box.height >= 44.0) shouldBe true
                    }
                }
                val past =
                    frame.host
                        .querySelectorAll("*")
                        .asList()
                        .map { it as Element }
                        .filter {
                            it.getBoundingClientRect().right > edge + 0.5
                        }.map { "${it.tagName}.${it.getAttribute("class")}@${it.getBoundingClientRect().right}" }
                withClue(past.take(12)) { root.scrollWidth - root.clientWidth shouldBe 0 }
            }
        }
    })

private const val NARROW_PHONE = 360
private const val PHONE_HEIGHT = 844
