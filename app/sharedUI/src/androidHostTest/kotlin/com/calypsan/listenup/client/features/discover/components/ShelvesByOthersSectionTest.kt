package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.shelf
import com.calypsan.listenup.client.features.discover.shelfOwner
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shelves by others: whose they are (a header that opens the owner) and what is on them (mosaic cards). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h2000dp")
class ShelvesByOthersSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private val maya =
        shelfOwner(
            "maya",
            "Maya Chen",
            shelf("sh1", "Cosy mysteries", bookCount = 8),
            shelf("sh2", "Space", bookCount = 3, coverCount = 2),
        )

    private fun show(
        users: List<DiscoverUserShelves>,
        onShelfClick: (String) -> Unit = {},
        onUserClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                ShelvesByOthersSection(
                    state = DiscoverShelvesUiState.Ready(users),
                    onShelfClick = onShelfClick,
                    onUserClick = onUserClick,
                )
            }
        }
    }

    @Test
    fun theHeadingIsShelvesByOthers() {
        show(listOf(maya))
        composeRule.onNode(hasText("Shelves by others") and isHeading()).assertExists()
    }

    @Test
    fun theOwnerHeaderNamesThemCountsTheirShelvesAndOpensTheirProfile() {
        var opened: String? = null
        show(listOf(maya), onUserClick = { opened = it })
        composeRule.onNode(hasText("Maya Chen") and hasText("2 shelves") and hasClickAction()).performClick()
        assertEquals("maya", opened)
    }

    @Test
    fun aShelfCardNamesItCountsItsBooksAndOpensIt() {
        var opened: String? = null
        show(listOf(maya), onShelfClick = { opened = it })
        composeRule.onNode(hasText("Cosy mysteries") and hasText("8 books") and hasClickAction()).performClick()
        assertEquals("sh1", opened)
    }

    @Test
    fun anOwnerShowsEachOfTheirShelves() {
        show(listOf(maya))
        composeRule.onAllNodes(hasText("books", substring = true) and hasClickAction()).assertCountEquals(2)
    }
}
