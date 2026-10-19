package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.discover.NOW_MS
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Activity: one wording ("Activity"), six rows then "See all activity" (which expands in place until
 * PR 6's screen, plan Q1), your own rows read "You", every row leads somewhere, book rows carry a cover,
 * and the avatar is its own profile link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class ActivityFeedSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun row(
        id: String,
        type: String,
        userId: String = "maya",
        name: String = "Maya Chen",
        bookId: String? = "b-$id",
        shelfId: String? = null,
        isYou: Boolean = false,
    ) = ActivityUiModel(
        id = id,
        userId = userId,
        type = type,
        occurredAt = NOW_MS,
        userDisplayName = name,
        bookId = bookId,
        bookTitle = "Piranesi",
        bookAuthorName = "Susanna Clarke",
        bookCoverPath = "/tmp/$id.webp",
        bookCoverHash = null,
        isReread = false,
        durationMs = 2_880_000,
        milestoneValue = 30,
        milestoneUnit = null,
        shelfId = shelfId,
        shelfName = shelfId?.let { "Sci-fi" },
        isYou = isYou,
    )

    private fun show(
        rows: List<ActivityUiModel>,
        onBookClick: (String) -> Unit = {},
        onShelfClick: (String) -> Unit = {},
        onUserClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                ActivityFeedSection(
                    state = ActivityFeedUiState.Ready(rows),
                    onBookClick = onBookClick,
                    onShelfClick = onShelfClick,
                    onUserClick = onUserClick,
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun theHeadingIsActivity() {
        show(listOf(row("a", "finished_book")))
        composeRule.onNode(hasText("Activity") and isHeading()).assertExists()
    }

    @Test
    fun sixRowsThenSeeAllExpandsInPlaceAndShowLessFoldsBack() {
        show((1..9).map { row("a$it", "finished_book") })
        composeRule.onAllNodes(hasText("finished", substring = true)).assertCountEquals(6)
        composeRule.onNodeWithText("See all activity").performClick()
        composeRule.onAllNodes(hasText("finished", substring = true)).assertCountEquals(9)
        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onAllNodes(hasText("finished", substring = true)).assertCountEquals(6)
    }

    @Test
    fun noSeeAllWhenEverythingFits() {
        show((1..4).map { row("a$it", "finished_book") })
        composeRule.onNodeWithText("See all activity").assertDoesNotExist()
    }

    @Test
    fun yourRowReadsYou() {
        show(listOf(row("a", "finished_book", userId = "me", name = "Simon Hull", isYou = true)))
        composeRule.onNodeWithText("You finished Piranesi by Susanna Clarke", substring = true).assertExists()
    }

    @Test
    fun aBookRowOpensTheBookAShelfRowTheShelfAndAJoinTheActor() {
        val opened = mutableListOf<String>()
        show(
            listOf(
                row("b", "finished_book"),
                row("s", "shelf_created", bookId = null, shelfId = "sh1"),
                row("j", "user_joined", userId = "ellie", name = "Ellie Ward", bookId = null),
            ),
            onBookClick = { opened += "book:$it" },
            onShelfClick = { opened += "shelf:$it" },
            onUserClick = { opened += "user:$it" },
        )
        composeRule.onNode(hasText("finished", substring = true) and hasClickAction()).performClick()
        composeRule.onNode(hasText("created the shelf", substring = true) and hasClickAction()).performClick()
        composeRule.onNode(hasText("joined the server", substring = true) and hasClickAction()).performClick()
        assertEquals(listOf("book:b-b", "shelf:sh1", "user:ellie"), opened)
    }

    @Test
    fun theAvatarIsItsOwnProfileLink() {
        show(listOf(row("a", "finished_book")))
        composeRule.onNodeWithContentDescription("Maya Chen's profile").assertHasClickAction()
    }

    @Test
    fun aBookRowCarriesItsCover() {
        show(listOf(row("a", "finished_book"), row("j", "user_joined", bookId = null)))
        composeRule.onAllNodesWithTag("activity-cover", useUnmergedTree = true).assertCountEquals(1)
    }
}
