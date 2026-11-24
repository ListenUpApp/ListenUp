package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rotation recreates the activity: the invite form must send what the admin chose before it, not
 * silently fall back to Member and seven days.
 */
@RunWith(RobolectricTestRunner::class)
class CreateInviteFormRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roleAndExpirySurviveASaveAndRestoreRoundTrip() {
        var submitted: Triple<String, String, Int>? = null
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                CreateInviteForm(
                    status = CreateInviteStatus.Idle,
                    onSubmit = { email, role, days -> submitted = Triple(email, role, days) },
                )
            }
        }
        composeRule.onNodeWithText("Admin").performClick()
        composeRule.onNodeWithText("30 days").performScrollTo().performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Create invite").performScrollTo().performClick()

        submitted shouldBe Triple("", "admin", 30)
    }
}
