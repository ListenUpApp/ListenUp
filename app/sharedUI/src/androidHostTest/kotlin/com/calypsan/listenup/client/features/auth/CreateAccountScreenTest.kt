package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * First-run account creation has five fields; losing them to a rotation is the worst case of the
 * recreation bug. These pin which survive a save/restore round trip and which must not.
 */
@RunWith(RobolectricTestRunner::class)
class CreateAccountScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun StateRestorationTester.setFieldsContent() =
        setContent {
            MaterialTheme {
                Column {
                    CreateAccountFields(
                        isLoading = false,
                        validationField = null,
                        submitLabel = "Create",
                        requirePasswordMatch = true,
                        onSubmit = { _, _, _, _, _ -> },
                    )
                }
            }
        }

    @Test
    fun `the first name survives a save and restore round trip`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setFieldsContent()
        composeRule.onNodeWithText("First name").performTextInput("Ada")

        restorationTester.emulateSavedInstanceStateRestore()

        editableTextOf("First name") shouldBe "Ada"
    }

    @Test
    fun `the password is not restored from saved state`() {
        // A password must never reach the saved-instance Bundle, which the system can persist to
        // disk. If someone later "fixes" rotation by making it saveable, this fails.
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setFieldsContent()
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
