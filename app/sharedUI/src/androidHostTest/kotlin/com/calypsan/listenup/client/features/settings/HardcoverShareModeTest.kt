package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec #1538 on Android: "Update Hardcover" chooses the mode, and the rows below say what it sends. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverShareModeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<HardcoverShareMode>()

    private fun render(
        shareMode: HardcoverShareMode,
        isSaving: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverSettingsContent(
                    state =
                        HardcoverSettingsUiState.Connected(
                            "simon",
                            1_790_424_000_000L,
                            false,
                            isMatchListKnown = true,
                            shareMode = shareMode,
                            isSavingShareMode = isSaving,
                        ),
                    isWide = false,
                    onConnect = {},
                    onOpenHardcover = {},
                    onCancelLinking = {},
                    onDisconnect = {},
                    onSyncNow = {},
                    onSetShareMode = { chosen += it },
                    onSendHistory = {},
                    onDismissHistory = {},
                    onFindMatch = {},
                    onOpenKeptOff = {},
                )
            }
        }
    }

    private fun option(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    @Test
    fun `As I listen is chosen, and the rows name starting, progress and finishing`() {
        render(HardcoverShareMode.AS_I_LISTEN)
        composeRule.onNodeWithText("Update Hardcover").performScrollTo().assertIsDisplayed()
        option("As I listen").performScrollTo().assertIsSelected()
        option("Only when I finish").assertIsNotSelected()
        listOf(
            "Books you start, as Currently reading",
            "How far you've listened",
            "Books you finish, marked as read",
        ).forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("Nothing is shared while you're still listening").assertDoesNotExist()
    }

    @Test
    fun `Only when I finish is chosen, and the rows say only finishing is shared, with its dates`() {
        render(HardcoverShareMode.FINISHED_ONLY)
        option("Only when I finish").performScrollTo().assertIsSelected()
        composeRule
            .onNodeWithText("Only books you finish, marked as read, with when you started and finished")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Nothing is shared while you're still listening").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("How far you've listened").assertDoesNotExist()
        composeRule.onNodeWithText("Books you start, as Currently reading").assertDoesNotExist()
    }

    @Test
    fun `choosing the other mode asks for it`() {
        render(HardcoverShareMode.AS_I_LISTEN)
        option("Only when I finish").performScrollTo().performClick()
        chosen shouldBe listOf(HardcoverShareMode.FINISHED_ONLY)
    }

    @Test
    fun `while a choice saves, neither option can be pressed`() {
        render(HardcoverShareMode.FINISHED_ONLY, isSaving = true)
        option("As I listen").performScrollTo().assertIsNotEnabled()
        option("Only when I finish").assertIsNotEnabled()
    }

    @Test
    fun `each option is a radio button at least 48dp tall`() {
        render(HardcoverShareMode.AS_I_LISTEN)
        listOf("As I listen", "Only when I finish").forEach { label ->
            option(label)
                .performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertHeightIsAtLeast(48.dp)
        }
    }
}
