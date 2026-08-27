package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.client.domain.model.ThemeMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document

/**
 * The theme seam.
 *
 * Worth its own spec because the dark palette shipped unreachable: every rule was in `web.css` and
 * nothing set the attribute they hang off. These are the two functions that make it reachable, so
 * they are the two that must not quietly stop working.
 */
class WebThemeTest :
    FunSpec({

        afterTest { document.documentElement?.removeAttribute("data-theme") }

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
    })
