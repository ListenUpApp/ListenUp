package com.calypsan.listenup.client.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.home.HeroChapter
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.3 Resume and §2.4 on Android. */
@RunWith(RobolectricTestRunner::class)
class HomeResumeHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val twoHoursAgo = Clock.System.now().toEpochMilliseconds() - 2 * 3_600_000L

    private fun resume(canPlay: Boolean = true) =
        HomeSlot.Resume(
            book =
                ContinueListeningBook(
                    bookId = "wok",
                    title = "The Way of Kings",
                    authorNames = "Brandon Sanderson",
                    narratorNames = "Michael Kramer, Kate Reading",
                    coverPath = "/tmp/cover-wok.webp",
                    progress = 0.11f,
                    currentPositionMs = 18_000_000L,
                    totalDurationMs = 163_800_000L,
                    lastPlayedAtMs = twoHoursAgo,
                    isDownloaded = false,
                ),
            next =
                NextInSeries(
                    bookId = "wor",
                    title = "Words of Radiance",
                    coverPath = "/tmp/cover-wor.webp",
                    coverHash = null,
                    narratorNames = null,
                    seriesName = "The Stormlight Archive",
                    sequenceLabel = "2",
                    durationMs = 173_700_000L,
                ),
            chapter = HeroChapter(number = 6, title = "Bridge Four"),
            canPlay = canPlay,
        )

    private fun show(
        slot: HomeSlot.Resume,
        onOpenBook: (String) -> Unit = {},
        onResume: (String) -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme { HomeResumeHero(slot, isOnlyBook = false, onOpenBook = onOpenBook, onResume = onResume) }
    }

    @Test
    fun `the hero says where you are in the book`() {
        show(resume())
        composeRule.onNodeWithText("Up next · 2h ago", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Read by Michael Kramer, Kate Reading", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ch. 6 · Bridge Four").assertIsDisplayed()
        composeRule.onNodeWithText("40h 30m left").assertIsDisplayed()
        composeRule.onNodeWithText("11% of 45h 30m").assertIsDisplayed()
    }

    @Test
    fun `Resume plays the book, and the cover and title open it`() {
        var played: String? = null
        var opened: String? = null
        show(resume(), onOpenBook = { opened = it }, onResume = { played = it })

        composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).assertIsEnabled().performClick()
        played shouldBe "wok"
        composeRule.onNodeWithTag(HOME_RESUME_IDENTITY_TAG).performClick()
        opened shouldBe "wok"
    }

    @Test
    fun `the next book in the series opens that book`() {
        var opened: String? = null
        show(resume(), onOpenBook = { opened = it })
        composeRule.onNodeWithText("Words of Radiance").performClick()
        opened shouldBe "wor"
    }

    @Test
    fun `a book that cannot play disables Resume and says why`() {
        show(resume(canPlay = false))
        composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).assertIsNotEnabled()
        composeRule
            .onNodeWithText("Can't reach your server. This book isn't downloaded, so it can't play right now.")
            .assertIsDisplayed()
    }
}
