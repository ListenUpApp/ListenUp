package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The inbox's release confirmation says how many books released — and, when some stayed held,
 * how many could not be, so a partial release never reads as a complete one.
 */
@RunWith(RobolectricTestRunner::class)
class AdminInboxReleaseConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a single released book reads in the singular`() {
        confirmationFor(released = 1, unreleased = 0) shouldBe "Released 1 book"
    }

    @Test
    fun `several released books read in the plural`() {
        confirmationFor(released = 3, unreleased = 0) shouldBe "Released 3 books"
    }

    @Test
    fun `a partial release says how many released and how many could not be`() {
        confirmationFor(released = 2, unreleased = 1) shouldBe "Released 2 of 3 books. 1 couldn't be released."
    }

    private fun confirmationFor(
        released: Int,
        unreleased: Int,
    ): String {
        var text = ""
        composeRule.setContent { text = releaseConfirmation(released = released, unreleased = unreleased) }
        composeRule.waitForIdle()
        return text
    }
}
