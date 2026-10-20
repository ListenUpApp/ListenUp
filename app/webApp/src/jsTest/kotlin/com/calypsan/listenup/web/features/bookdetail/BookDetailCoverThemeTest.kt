package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.web.FakeCoverThemes
import com.calypsan.listenup.web.KINGS_THEMES
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.computedContrast
import com.calypsan.listenup.web.design.LocalCoverThemes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.css.CSSMediaRule
import org.w3c.dom.css.CSSStyleRule
import org.w3c.dom.css.CSSStyleSheet

/** Book Detail spec §2.3 on the web: the hero's wash, track and action come from the cover; neutral stays neutral. */
class BookDetailCoverThemeTest :
    FunSpec({
        val mounts = MountRegistry()
        val frames = ViewportFrames()
        afterTest {
            mounts.disposeAll()
            frames.disposeAll()
        }

        val ready =
            readyBook(readingStage = ReadingStage.InProgress)
                .copy(primaryAction = PrimaryAction.Resume, progress = 0.3f)

        fun hero(source: CoverThemeRepository?): HTMLElement =
            mounts
                .mount {
                    CompositionLocalProvider(LocalCoverThemes provides source) {
                        BookDetailPage(state = ready, onPlayFromChapter = {}, onOpenLibrary = {}, onPlay = {}, onRetryConnection = {})
                    }
                }.querySelector(".bd-head") as HTMLElement

        test("with no source the hero is exactly as before: no class, no custom properties") {
            val head = hero(null)
            head.classList.contains("cover-themed") shouldBe false
            head.getAttribute("style") shouldBe null
        }

        test("a cover known from an earlier visit paints at once, with no crossfade") {
            val head = hero(FakeCoverThemes(known = KINGS_THEMES))
            head.classList.contains("cover-themed") shouldBe true
            head.classList.contains("cover-fade") shouldBe false
            head.style.getPropertyValue("--cover-wash-l") shouldBe "#E4E4E6"
            head.style.getPropertyValue("--cover-action-d") shouldBe "#CC2F2A"
        }

        test("a first visit is neutral, then crossfades in when the colours arrive") {
            val source = FakeCoverThemes(known = null)
            val head = hero(source)
            head.classList.contains("cover-themed") shouldBe false
            source.live.emit(KINGS_THEMES)
            awaitFrame()
            head.classList.contains("cover-themed") shouldBe true
            head.classList.contains("cover-fade") shouldBe true
        }

        test("a neutral answer (near-coral, grey) leaves the hero untouched") {
            val source = FakeCoverThemes(known = null)
            val head = hero(source)
            source.live.emit(null)
            awaitFrame()
            head.classList.contains("cover-themed") shouldBe false
        }

        listOf(false, true).forEach { dark ->
            test("${if (dark) "dark" else "light"}, with the real sheet: the label on Resume and the ink on the wash read at 4.5:1") {
                val frame =
                    frames.mount(width = 1280, height = 900) {
                        CompositionLocalProvider(LocalCoverThemes provides FakeCoverThemes(known = KINGS_THEMES)) {
                            BookDetailPage(state = ready, onPlayFromChapter = {}, onOpenLibrary = {}, onPlay = {}, onRetryConnection = {})
                        }
                    }
                val root = frame.host.ownerDocument!!.documentElement!!
                if (dark) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
                val primary = frame.find(".bd-primary")
                computedContrast(frame.css(primary, "color"), frame.css(primary, "background-color")) shouldBeGreaterThanOrEqual 4.5
                val head = frame.find(".bd-head")
                computedContrast(
                    frame.css(frame.find(".bd-head h1"), "color"),
                    frame.css(head, "background-color"),
                ) shouldBeGreaterThanOrEqual
                    4.5
                frame.css(frame.find(".bd-head .progress-fill"), "background-color") shouldBe frame.css(primary, "background-color")
            }
        }

        test("the crossfade lives only under prefers-reduced-motion: no-preference") {
            val rules =
                document.styleSheets
                    .asList()
                    .filterIsInstance<CSSStyleSheet>()
                    .flatMap { it.cssRules.asList() }
            val fadeRules = rules.filterIsInstance<CSSStyleRule>().filter { ".cover-fade" in it.selectorText }
            fadeRules.all { it.style.getPropertyValue("transition").isEmpty() } shouldBe true
            val gated =
                rules
                    .filterIsInstance<CSSMediaRule>()
                    .filter { "prefers-reduced-motion: no-preference" in it.media.mediaText }
                    .flatMap { it.cssRules.asList() }
                    .filterIsInstance<CSSStyleRule>()
                    .filter { ".cover-fade" in it.selectorText }
            gated.isNotEmpty() shouldBe true
        }
    })
