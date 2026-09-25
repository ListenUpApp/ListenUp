package com.calypsan.listenup.client.navigation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The connection banner is drawn over the whole navigation stack. On the Login page its "Sign in"
 * points at the page already open, and as that page slides in underneath, the pinned banner reads
 * as floating loose (reported 2026-09-25). iOS presents sign-in as a sheet over its banner; Android
 * hides the banner while Login is on top.
 */
class ConnectionBannerPlacementTest :
    FunSpec({
        test("the banner steps aside while the Login page is on top") {
            showsConnectionBannerOver(Login) shouldBe false
        }

        test("the banner shows over the shell and every other page") {
            showsConnectionBannerOver(Shell) shouldBe true
            showsConnectionBannerOver(null) shouldBe true
        }
    })
