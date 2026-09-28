package com.calypsan.listenup.client.design.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.calypsan.listenup.client.design.theme.AA_TEXT
import com.calypsan.listenup.client.design.theme.DarkColorScheme
import com.calypsan.listenup.client.design.theme.LightColorScheme
import com.calypsan.listenup.client.design.theme.contrastRatio
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

class CoverScrimContrastTest :
    FunSpec({
        // The worst case is the lightest possible artwork: a pure-white cover.
        val whiteCover = Color.White

        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            test("$name: scrim ink clears AA for text over a white cover") {
                val scrimOverCover = scheme.scrim.copy(alpha = CoverScrimDefaults.ALPHA).compositeOver(whiteCover)
                withClue("ContentColor on scrim@${CoverScrimDefaults.ALPHA} over white") {
                    contrastRatio(CoverScrimDefaults.ContentColor, scrimOverCover) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
        }
    })
