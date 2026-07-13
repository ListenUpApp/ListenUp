package com.calypsan.listenup.client.features.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CampfireVisibilityTest :
    FunSpec({
        test("solo player: full screen shows only when expanded with an active book") {
            campfireFullScreenVisible(isExpanded = true, hasActiveBook = true, hasSession = false, minimized = false) shouldBe true
            campfireFullScreenVisible(isExpanded = false, hasActiveBook = true, hasSession = false, minimized = false) shouldBe false
            campfireFullScreenVisible(isExpanded = true, hasActiveBook = false, hasSession = false, minimized = false) shouldBe false
        }

        test("live campfire takes over full screen unless minimized") {
            campfireFullScreenVisible(isExpanded = false, hasActiveBook = false, hasSession = true, minimized = false) shouldBe true
            campfireFullScreenVisible(isExpanded = false, hasActiveBook = false, hasSession = true, minimized = true) shouldBe false
        }

        test("minimizing the campfire does not suppress an independently-expanded solo player") {
            campfireFullScreenVisible(isExpanded = true, hasActiveBook = true, hasSession = true, minimized = true) shouldBe true
        }

        test("campfire flow fills the container only when a session+book exist and it is not minimized") {
            campfireFlowShown(hasSession = true, hasBook = true, minimized = false) shouldBe true
            campfireFlowShown(hasSession = true, hasBook = true, minimized = true) shouldBe false
            campfireFlowShown(hasSession = false, hasBook = true, minimized = false) shouldBe false
            campfireFlowShown(hasSession = true, hasBook = false, minimized = false) shouldBe false
        }
    })
