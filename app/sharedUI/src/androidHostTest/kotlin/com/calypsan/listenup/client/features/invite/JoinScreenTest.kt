package com.calypsan.listenup.client.features.invite

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.api.dto.invite.InvitePreview
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The invite claim form: a name the person typed survives a rotation, the password they chose
 * does not travel through saved instance state.
 */
@RunWith(RobolectricTestRunner::class)
class JoinScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun StateRestorationTester.setClaimContent() =
        setContent {
            MaterialTheme {
                ClaimStep(
                    preview =
                        InvitePreview(
                            displayName = "Ada Lovelace",
                            email = "ada@example.com",
                            invitedByName = "Simon",
                            serverName = "Home",
                            valid = true,
                        ),
                    onClaim = { _, _, _ -> },
                    onCancel = {},
                )
            }
        }

    @Test
    fun `the first name survives a save and restore round trip`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setClaimContent()
        composeRule.onNodeWithText("First name").performTextInput("Ada")

        restorationTester.emulateSavedInstanceStateRestore()

        editableTextOf("First name") shouldBe "Ada"
    }

    @Test
    fun `the password is not restored from saved state`() {
        // A password must never reach the saved-instance Bundle, which the system can persist to
        // disk. If someone later "fixes" rotation by making it saveable, this fails.
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setClaimContent()
        composeRule.onNodeWithText("Password").performTextInput("s3cret-pass")
        editableTextOf("Password").length shouldBe "s3cret-pass".length

        restorationTester.emulateSavedInstanceStateRestore()

        editableTextOf("Password") shouldBe ""
    }

    private fun editableTextOf(label: String): String =
        composeRule
            .onNodeWithText(label)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
}
