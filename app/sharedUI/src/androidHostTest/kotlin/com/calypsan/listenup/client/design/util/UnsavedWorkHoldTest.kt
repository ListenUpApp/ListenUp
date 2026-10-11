package com.calypsan.listenup.client.design.util

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen holding unsaved work raises a hold for as long as it is composed and has the work; the
 * navigation host reads the hold before an outside navigation would clear the back stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class UnsavedWorkHoldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val holds = UnsavedWorkHolds()

    @Test
    fun nothingIsHeldBeforeAnyScreenHoldsWork() {
        holds.isHeld shouldBe false
    }

    @Test
    fun aScreenWithUnsavedWorkHoldsItWhileComposed() {
        var visible by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalUnsavedWorkHolds provides holds) {
                if (visible) HoldUnsavedWork(active = true)
            }
        }
        composeRule.waitForIdle()
        holds.isHeld shouldBe true

        visible = false
        composeRule.waitForIdle()
        holds.isHeld shouldBe false
    }

    @Test
    fun savingTheWorkReleasesTheHold() {
        var unsaved by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalUnsavedWorkHolds provides holds) {
                HoldUnsavedWork(active = unsaved)
            }
        }
        composeRule.waitForIdle()
        holds.isHeld shouldBe true

        unsaved = false
        composeRule.waitForIdle()
        holds.isHeld shouldBe false
    }

    @Test
    fun twoScreensHoldIndependently() {
        var firstUnsaved by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalUnsavedWorkHolds provides holds) {
                HoldUnsavedWork(active = firstUnsaved)
                HoldUnsavedWork(active = true)
            }
        }
        composeRule.waitForIdle()

        firstUnsaved = false
        composeRule.waitForIdle()

        holds.isHeld shouldBe true
    }
}
