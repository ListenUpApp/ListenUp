package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.client.domain.model.ThemeMode
import io.kotest.core.spec.style.FunSpec
import com.calypsan.listenup.core.BrowserSecureStorage
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await

/**
 * The theme seam.
 *
 * Worth its own spec because the dark palette shipped unreachable: every rule was in `web.css` and
 * nothing set the attribute they hang off. These are the two functions that make it reachable, so
 * they are the two that must not quietly stop working.
 */
class WebThemeTest :
    FunSpec({

        afterTest {
            document.documentElement?.removeAttribute("data-theme")
            BrowserSecureStorage().delete(THEME_KEY)
        }

        test("an explicit choice ignores what the OS says") {
            shouldUseDarkTheme(ThemeMode.DARK, systemPrefersDark = false) shouldBe true
            shouldUseDarkTheme(ThemeMode.LIGHT, systemPrefersDark = true) shouldBe false
        }

        test("following the system means following it in both directions") {
            shouldUseDarkTheme(ThemeMode.SYSTEM, systemPrefersDark = true) shouldBe true
            shouldUseDarkTheme(ThemeMode.SYSTEM, systemPrefersDark = false) shouldBe false
        }

        test("applying dark sets the attribute the sheet keys on") {
            applyTheme(dark = true)

            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }

        test("going light removes the attribute rather than setting it to a value") {
            // The sheet has no `[data-theme="light"]` rule — light IS the absence of the attribute.
            // Setting it to "light" would compile, pass a naive assertion, and render dark.
            applyTheme(dark = true)
            applyTheme(dark = false)

            document.documentElement?.hasAttribute("data-theme") shouldBe false
        }

        test("applying the same theme twice leaves it where it was") {
            applyTheme(dark = true)
            applyTheme(dark = true)

            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }

        // The seed in index.html runs before the bundle, so it reads storage by hand. Written here
        // through the real storage class, so a renamed namespace or a changed value format fails
        // this rather than quietly painting the wrong theme first.
        test("the pre-paint seed paints a stored Dark before the app has loaded") {
            BrowserSecureStorage().save(THEME_KEY, ThemeMode.DARK.toStorageString())

            runPrePaintSeed()

            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }

        test("the pre-paint seed leaves a stored Light light, whatever the OS says") {
            BrowserSecureStorage().save(THEME_KEY, ThemeMode.LIGHT.toStorageString())
            document.documentElement?.setAttribute("data-theme", "dark")

            runPrePaintSeed()

            document.documentElement?.hasAttribute("data-theme") shouldBe false
        }

        test("with nothing stored, the pre-paint seed follows the OS, as SYSTEM does") {
            runPrePaintSeed()

            document.documentElement?.hasAttribute("data-theme") shouldBe systemPrefersDark()
        }
    })

/** `SettingsRepositoryImpl`'s key for the theme mode, before `BrowserSecureStorage` namespaces it. */
private const val THEME_KEY = "theme_mode"

/** Runs the inline script `index.html` carries in its head, exactly as the page would. */
private suspend fun runPrePaintSeed() {
    val page =
        window
            .fetch("/index.html")
            .await()
            .text()
            .await()
    val source =
        Regex("""<script>([\s\S]*?)</script>""").find(page)?.groupValues?.get(1)
            ?: error("index.html carries no inline pre-paint script")
    val seed: dynamic = js("Function")
    seed(source)()
}
