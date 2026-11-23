package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The keyboard's Done key creates the collection, under the Create button's own rule: a blank
 * name does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CreateCollectionDialogKeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val created = mutableListOf<String>()

    @Test
    fun `Done creates the named collection`() {
        setContent()

        composeRule.onNode(hasSetTextAction()).performTextInput("Staff picks")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        created shouldBe listOf("Staff picks")
    }

    @Test
    fun `Done on a blank name creates nothing`() {
        setContent()

        composeRule.onNode(hasSetTextAction()).performImeAction()

        created shouldBe emptyList()
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                CreateCollectionDialog(isCreating = false, onDismiss = {}, onConfirm = { created += it })
            }
        }
    }
}
