package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The palette's shortcut is named the way the reader's keyboard labels it. "⌘K" on a Windows or
 * Linux machine names a key that is not there; the handler has always taken Ctrl as well.
 */
class ShortcutLabelTest :
    FunSpec({
        listOf("MacIntel", "macOS", "iPhone", "iPad").forEach { platform ->
            test("$platform is shown ⌘K") {
                paletteShortcutLabel(platform) shouldBe "⌘K"
            }
        }

        listOf("Win32", "Windows", "Linux x86_64", "Linux", "Android", "").forEach { platform ->
            test("'$platform' is shown Ctrl K") {
                paletteShortcutLabel(platform) shouldBe "Ctrl K"
            }
        }
    })
