package com.calypsan.listenup.client.presentation.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [NowPlayingSheetState] is the singleton that replaced [NowPlayingViewModel]'s per-instance
 * `isExpandedFlow` — the piece that let a `factory`-scoped VM still present one shared expansion
 * flag across every `koinViewModel()` consumer.
 */
class NowPlayingSheetStateTest :
    FunSpec({
        test("starts collapsed") {
            NowPlayingSheetState().isExpanded.value shouldBe false
        }

        test("expand and collapse update the shared flow") {
            val state = NowPlayingSheetState()

            state.expand()
            state.isExpanded.value shouldBe true

            state.collapse()
            state.isExpanded.value shouldBe false
        }
    })
