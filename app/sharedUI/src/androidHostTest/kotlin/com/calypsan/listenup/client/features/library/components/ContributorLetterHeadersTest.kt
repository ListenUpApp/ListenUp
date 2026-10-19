package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isHeading
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.design.image.startKoinForContributorPhotos
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.stopKoin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec L25: Authors and Narrators sorted by name carry letter headers from the shared sections, as
 * Books does, so a reader scanning the list sees where each letter starts. Any other sort has none.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorLetterHeadersTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val people = listOf(person("c1", "Andy Weir"), person("c2", "Ann Leckie"), person("c3", "Zoe Quinn"))
    private val byName = SortState(SortCategory.NAME, SortDirection.ASCENDING)
    private val letters = listOf(LetterSection("A", 0, 2), LetterSection("Z", 2, 1))

    /** The rows' avatars resolve their photos through the GLOBAL Koin context. */
    @Before
    fun startKoinForAvatars() {
        startKoinForContributorPhotos("/tmp/contributor-none.jpg")
    }

    @After
    fun stopKoinAfterAvatars() {
        stopKoin()
    }

    @Test
    fun `authors by name get a header per letter`() {
        composeRule.setContent {
            MaterialTheme {
                AuthorsContent(
                    authors = people,
                    sortState = byName,
                    letters = letters,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onAuthorClick = {},
                )
            }
        }
        // Headings, so TalkBack can move letter to letter; the index's own letters are not headings.
        val headings = composeRule.onAllNodes(isHeading())
        headings.assertCountEquals(2)
        headings[0].assertTextEquals("A")
        headings[1].assertTextEquals("Z")
    }

    @Test
    fun `narrators by name get a header per letter`() {
        composeRule.setContent {
            MaterialTheme {
                NarratorsContent(
                    narrators = people,
                    sortState = byName,
                    letters = letters,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onNarratorClick = {},
                )
            }
        }
        // Headings, so TalkBack can move letter to letter; the index's own letters are not headings.
        val headings = composeRule.onAllNodes(isHeading())
        headings.assertCountEquals(2)
        headings[0].assertTextEquals("A")
        headings[1].assertTextEquals("Z")
    }

    @Test
    fun `a book-count sort has no headers`() {
        composeRule.setContent {
            MaterialTheme {
                AuthorsContent(
                    authors = people,
                    sortState = SortState(SortCategory.BOOK_COUNT, SortDirection.DESCENDING),
                    letters = emptyList(),
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onAuthorClick = {},
                )
            }
        }
        composeRule.onAllNodes(isHeading()).assertCountEquals(0)
    }

    private fun person(
        id: String,
        name: String,
    ) = ContributorWithBookCount(contributor = Contributor(id = ContributorId(id), name = name), bookCount = 1)
}
