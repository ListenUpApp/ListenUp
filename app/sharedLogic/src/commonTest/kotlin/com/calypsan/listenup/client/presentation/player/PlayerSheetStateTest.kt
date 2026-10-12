package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [PlayerSheetState] is the singleton that holds the one expansion flag every player view model instance
 * shares — the piece that lets a `factory`-scoped VM still present one expansion across every consumer.
 */
class PlayerSheetStateTest :
    FunSpec({
        test("starts collapsed") {
            PlayerSheetState().expanded.value shouldBe false
        }

        test("expand and collapse update the shared flow") {
            val state = PlayerSheetState()

            state.expand()
            state.expanded.value shouldBe true

            state.collapse()
            state.expanded.value shouldBe false
        }
    })
