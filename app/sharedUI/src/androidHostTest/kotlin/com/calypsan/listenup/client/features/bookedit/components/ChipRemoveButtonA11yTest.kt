package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.domain.model.EditableCollection
import com.calypsan.listenup.client.features.contributoredit.components.AliasesSection
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A chip's trailing ✕ is a button: TalkBack announces it as one, and a finger gets a full 48dp
 * target even though the glyph stays the chip's 24dp.
 */
@RunWith(RobolectricTestRunner::class)
class ChipRemoveButtonA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a collection chip's remove control is a full-size button`() {
        composeRule.setContent {
            MaterialTheme {
                CollectionsSubsection(
                    collections = listOf(EditableCollection(id = "c1", name = "Favourites")),
                    searchQuery = "",
                    searchResults = emptyList(),
                    onSearchQueryChange = {},
                    onCollectionSelected = {},
                    onRemoveCollection = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Remove Favourites")
            .assert(isButton)
            .assertTouchTargetAtLeast()
    }

    @Test
    fun `an alias chip's remove control is a full-size button`() {
        composeRule.setContent {
            MaterialTheme {
                AliasesSection(
                    aliases = listOf("Robert Galbraith"),
                    onUnmerge = {},
                    onMergeClick = {},
                    canCurate = true,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Remove Robert Galbraith")
            .assert(isButton)
            .assertTouchTargetAtLeast()
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
}
