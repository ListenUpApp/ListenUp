package com.calypsan.listenup.client.features.contributordetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Adjust photo sits in the contributor's overflow menu, for someone who may edit metadata, when there is a photo. */
@RunWith(RobolectricTestRunner::class)
class ContributorAdjustPhotoMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private var adjusted = false

    private fun show(
        canEditMetadata: Boolean,
        hasPhoto: Boolean,
    ) = compose.setContent {
        MaterialTheme {
            NavigationBar(
                onBackClick = {},
                onEditClick = {},
                onMatchDetails = {},
                onDeleteClick = {},
                onAdjustPhoto =
                    if (hasPhoto) {
                        { adjusted = true }
                    } else {
                        null
                    },
                canEditMetadata = canEditMetadata,
                canCurateLibrary = false,
            )
        }
    }

    @Test
    fun `an editor sees Adjust photo when there is a photo, and it opens the editor`() {
        show(canEditMetadata = true, hasPhoto = true)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Adjust photo").performClick()
        adjusted shouldBe true
    }

    @Test
    fun `no photo, no Adjust photo`() {
        show(canEditMetadata = true, hasPhoto = false)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Adjust photo").assertDoesNotExist()
    }

    @Test
    fun `a member without Edit metadata gets no menu and so no Adjust photo`() {
        show(canEditMetadata = false, hasPhoto = true)
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
    }
}
