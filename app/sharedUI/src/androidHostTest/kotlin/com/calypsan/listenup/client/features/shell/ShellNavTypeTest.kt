package com.calypsan.listenup.client.features.shell

import androidx.window.core.layout.WindowSizeClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShellNavTypeTest :
    FunSpec({
        // WindowSizeClass.compute() snaps to standard breakpoints (0 / 600 / 840), so
        // 930dp and 1200dp would land in the same bucket and be indistinguishable.
        // The (Int, Int) constructor sets minWidthDp directly, which lets isWidthAtLeastBreakpoint
        // compare against our custom 1000dp expanded threshold without rounding.
        fun classOf(widthDp: Int) = WindowSizeClass(widthDp, 800)

        test("compact width uses the bottom bar") {
            shellNavType(classOf(400)) shouldBe ShellNavType.BottomBar
        }
        test("medium width uses the collapsed rail") {
            shellNavType(classOf(700)) shouldBe ShellNavType.RailCollapsed
        }
        test("foldable width (930dp) stays on the collapsed rail, not expanded") {
            shellNavType(classOf(930)) shouldBe ShellNavType.RailCollapsed
        }
        test("expanded width (>=1000dp) uses the expanded rail") {
            shellNavType(classOf(1200)) shouldBe ShellNavType.RailExpanded
        }
    })
