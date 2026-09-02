package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.core.GenreId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val FANTASY = Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy")
private val HORROR = Genre(id = "g2", name = "Horror", slug = "horror", path = "/fiction/horror")
private val FOUND_FAMILY = Tag(id = "t1", name = "Found Family", slug = "found-family")
private val FEEL_GOOD = Mood(id = "m1", name = "Feel-Good", slug = "feel-good")

/**
 * The pickers that **add** rather than replace, and the two promises they have to keep.
 *
 * The first is the one the whole screen rests on: a field nobody touches writes to nothing, said in
 * words rather than implied by an empty box. The second is quieter and easier to get wrong — a tag
 * is carried by its **display name**, because the repository slugifies server-side, and a slug
 * passed in its place would mint a tag literally called `found-family` for everyone, permanently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class BulkEditRelationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun editing(edits: List<BulkEdit> = emptyList()) =
        BulkEditUiState.Editing(
            bookCount = 40,
            edits = edits,
            preview = edits.map { BulkEditPreviewRow(edit = it, affectedCount = 12) },
            changedBookCount = if (edits.isEmpty()) 0 else 12,
        )

    private fun renderCredits(
        state: BulkEditUiState.Editing,
        actions: BulkEditFormActions = BulkEditFormActions(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                BulkEditCredits(state = state, offers = BulkEditOffers(), actions = actions)
            }
        }
        composeRule.waitForIdle()
    }

    private fun render(
        state: BulkEditUiState.Editing,
        actions: BulkEditFormActions = BulkEditFormActions(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                BulkEditClassification(
                    state = state,
                    offers =
                        BulkEditOffers(
                            genres = listOf(FANTASY, HORROR),
                            tags = listOf(FOUND_FAMILY),
                            moods = listOf(FEEL_GOOD),
                        ),
                    actions = actions,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an untouched picker promises to add nothing`() {
        render(editing())

        // Three fields, three identical promises — the assertion is that every one of them says it.
        composeRule
            .onAllNodesWithText("Nothing is added unless you pick something.", useUnmergedTree = true)
            .assertCountEquals(3)
    }

    @Test
    fun `picking a genre records it`() {
        var chosen: List<BookGenreInput>? = null
        render(editing(), BulkEditFormActions(onGenresChange = { chosen = it }))

        composeRule.onNodeWithText("Search genres").performTextInput("Fant")
        composeRule.onNodeWithText("Fantasy").performClick()

        chosen?.map { it.genreId.value } shouldBe listOf("g1")
    }

    @Test
    fun `an armed picker counts the books it would actually reach`() {
        // Twelve of forty, not forty: the count is this field's own preview row, because the books
        // that already carry the genre are not books this changes.
        render(editing(listOf(BulkEdit.AddGenres(listOf(BookGenreInput(genreId = GenreId("g1")))))))

        composeRule
            .onNodeWithText("Written to 12 of 40 books.", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a tag is carried by the name a reader sees, never its slug`() {
        var chosen: List<String>? = null
        render(editing(), BulkEditFormActions(onTagsChange = { chosen = it }))

        composeRule.onNodeWithText("Search tags").performTextInput("Found")
        composeRule.onNodeWithText("Found Family").performClick()

        chosen shouldBe listOf("Found Family")
    }

    @Test
    fun `a narrator the library has never seen can still be credited`() {
        // The contract carries a contributor by name with a null id and the server resolves-or-
        // creates it — the same path the single-book editor takes. Refusing here would send someone
        // to edit a box set one book at a time.
        var credited: List<BookContributorInput>? = null
        renderCredits(editing(), BulkEditFormActions(onContributorsChange = { credited = it }))

        composeRule.onNodeWithText("Search people").performTextInput("Wil Wheaton")
        composeRule.onNodeWithText("Add \u201cWil Wheaton\u201d", useUnmergedTree = true).performClick()

        credited?.single()?.name shouldBe "Wil Wheaton"
        withClue("no id, because the library has no such person yet") {
            credited?.single()?.id shouldBe null
        }
        withClue("credited in the role the picker was set to") {
            credited?.single()?.role shouldBe "author"
        }
    }

    @Test
    fun `a genre the library does not hold cannot be invented`() {
        // Genres, tags and moods stay existing-only: minting one across forty books is how a library
        // ends up with three spellings of the same thing.
        render(editing())

        composeRule.onNodeWithText("Search genres").performTextInput("Steampunk")

        composeRule.onNodeWithText("Add \u201cSteampunk\u201d", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a picked mood is offered as a chip and can be taken back off`() {
        var chosen: List<String>? = null
        render(
            editing(listOf(BulkEdit.AddMoods(listOf("Feel-Good")))),
            BulkEditFormActions(onMoodsChange = { chosen = it }),
        )

        composeRule
            .onNodeWithContentDescription("Remove Feel-Good from this edit", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Feel-Good").performClick()

        chosen.orEmpty().shouldBeEmpty()
    }
}
