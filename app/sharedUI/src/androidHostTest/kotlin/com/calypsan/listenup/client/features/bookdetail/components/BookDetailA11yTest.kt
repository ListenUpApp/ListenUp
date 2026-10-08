package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Book Detail with TalkBack (#1562): the sections are headings a reader can jump between, a count is read
 * with what it counts, and a reader row says whose profile it opens and where a read was logged.
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar's composable KoinApplication registers itself globally; cleared on both sides. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private val reader =
        ReaderRowUi(
            userId = "u1",
            name = "Ada",
            isReading = false,
            progressPct = null,
            finishedWhen = "May 2016",
            isOnHardcover = true,
        )

    @Test
    fun `Chapters is a heading read with its count`() {
        composeRule.setContent { MaterialTheme { ChaptersHeader(chapterCount = 19) } }

        composeRule.onNode(isHeading() and hasText("Chapters") and hasContentDescription("19 chapters")).assertExists()
    }

    @Test
    fun `Readers is a heading read with its count`() {
        composeRule.setContent {
            WithAvatarDependencies {
                BookReadersContent(
                    readers = listOf(reader),
                    listeningNowCount = 0,
                    totalCount = 1,
                    isCard = false,
                    onUserClick = {},
                )
            }
        }

        composeRule.onNode(isHeading() and hasText("Readers") and hasContentDescription("1 reader")).assertExists()
    }

    @Test
    fun `Details is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                DetailsSection(
                    publisher = "Tor",
                    publishYear = 2022,
                    language = null,
                    audioFiles = emptyList(),
                    credits = emptyList(),
                    onContributorClick = {},
                )
            }
        }

        composeRule.onNode(isHeading() and hasText("Details")).assertExists()
    }

    @Test
    fun `the Hardcover card's needs-a-match and matched titles are headings`() {
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(state = BookHardcoverUiState.NeedsMatch, onFindMatch = {}, onRemoveMatch = {}, onSetSynced = {})
            }
        }
        composeRule.onNode(isHeading() and hasText("Needs a match")).assertExists()
    }

    @Test
    fun `a never-matched book says so under the switch, in reading order`() {
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(state = BookHardcoverUiState.Unmatched, onFindMatch = {}, onRemoveMatch = {}, onSetSynced = {})
            }
        }

        val switch = composeRule.onNode(hasText("Sync with Hardcover")).getUnclippedBoundsInRoot()
        val line = composeRule.onNode(hasText(NEVER_MATCHED)).assertIsDisplayed().getUnclippedBoundsInRoot()
        line.top shouldBeGreaterThanOrEqualTo switch.bottom
    }

    @Test
    fun `a matched book's title is a heading`() {
        val match = HardcoverMatchedBook(1L, "The Two Towers", listOf("J.R.R. Tolkien"), 1954, chosenByYou = false)
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(
                    state = BookHardcoverUiState.Linked(match, HardcoverBookSync.UP_TO_DATE),
                    onFindMatch = {},
                    onRemoveMatch = {},
                    onSetSynced = {},
                )
            }
        }
        composeRule.onNode(isHeading() and hasText("The Two Towers")).assertExists()
    }

    @Test
    fun `a reader row says it opens a profile, and reads a Hardcover read as one phrase`() {
        composeRule.setContent { WithAvatarDependencies { ReaderRow(reader = reader, onUserClick = {}) } }

        val row = composeRule.onNode(hasClickAction() and hasText("Ada")).fetchSemanticsNode()
        row.config[SemanticsActions.OnClick].label shouldBe "View profile"
        // The avatar no longer repeats the name, and the date and the label are one phrase.
        row.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } shouldBe listOf("Read May 2016 on Hardcover")
    }

    private companion object {
        const val NEVER_MATCHED = "Not matched yet. ListenUp looks for it on Hardcover when you start listening."
    }

    @Composable
    private fun WithAvatarDependencies(content: @Composable () -> Unit) {
        val profiles =
            mock<UserProfileRepository>(MockMode.autoUnit) {
                every { observeProfile(any()) } returns flowOf<CachedUserProfile?>(null)
            }
        val storage =
            mock<ImageStorage>(MockMode.autoUnit) {
                every { userAvatarExists(any()) } returns false
                every { getUserAvatarPath(any()) } returns "/tmp/avatar"
            }
        val images =
            mock<ImageRepository>(MockMode.autoUnit) {
                everySuspend { downloadUserAvatar(any(), any()) } returns AppResult.Success(false)
            }
        KoinApplication(
            application = {
                modules(
                    module {
                        single { profiles }
                        single { storage }
                        single { images }
                    },
                )
            },
        ) {
            MaterialTheme { content() }
        }
    }
}
