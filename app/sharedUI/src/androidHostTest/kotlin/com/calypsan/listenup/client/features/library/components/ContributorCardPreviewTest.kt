package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import com.calypsan.listenup.client.design.components.AlphabetRailWidth
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.robolectric.annotation.GraphicsMode
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * A wide Library people row (spec c7) shows the contributor's two newest covers beside their name; a phone row does
 * not. The covers are decoration: TalkBack reads the row exactly as before.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ContributorCardPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Avatars and covers without a local path resolve through the GLOBAL Koin context. */
    @Before
    fun startKoinForImages() {
        startKoin {
            modules(
                module {
                    single<ImageRepository> {
                        mock(MockMode.autoUnit) {
                            every { getContributorImagePath(any()) } returns "/tmp/contributor-none.jpg"
                            every { contributorImageExists(any()) } returns true
                            every { getBookCoverPath(any()) } returns "/tmp/cover-none.jpg"
                            every { bookCoverExists(any()) } returns true
                        }
                    }
                    single<ServerConfig> { mock(MockMode.autoUnit) }
                    single<AuthSession> { mock(MockMode.autoUnit) }
                },
            )
        }
    }

    @After
    fun stopKoinAfterImages() {
        stopKoin()
    }

    private val ann =
        ContributorWithBookCount(
            contributor = Contributor(id = ContributorId("ann"), name = "Ann Leckie"),
            bookCount = 3,
            previewBookIds = listOf("new", "mid"),
        )

    private fun render(
        showCovers: Boolean,
        reserveRail: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ContributorCard(contributorWithCount = ann, onClick = {}, showCovers = showCovers, reserveRail = reserveRail)
            }
        }
    }

    @Test
    fun `beside the letter index the covers stop short of it`() {
        render(showCovers = true, reserveRail = true)

        val row = composeRule.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        composeRule.onAllNodesWithTag(PREVIEW_COVER_TAG, useUnmergedTree = true).fetchSemanticsNodes().forEach { cover ->
            val right = with(composeRule.density) { cover.boundsInRoot.right.toDp() }
            right shouldBeLessThanOrEqualTo row.right - AlphabetRailWidth
        }
    }

    @Test
    fun `a book with no cover still shows a tile, as web's waiting tile does`() {
        render(showCovers = true)
        composeRule.waitForIdle()

        val tile = composeRule.onAllNodesWithTag(PREVIEW_COVER_TAG, useUnmergedTree = true)[0].captureToImage().toPixelMap()
        // The middle of a missing cover is the placeholder's colour, not the card showing through a hole.
        val middle = tile[tile.width / 2, tile.height / 2]
        val card = composeRule.onNode(hasClickAction()).captureToImage().toPixelMap()
        (middle != card[2, card.height / 2]) shouldBe true
    }

    @Test
    fun `on wide a row with two preview ids draws two square covers, hidden from TalkBack, and its label is unchanged`() {
        render(showCovers = true)

        composeRule.onAllNodesWithTag(PREVIEW_COVER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 2
        val row = composeRule.onNode(hasClickAction()).fetchSemanticsNode()
        row.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } shouldContainExactly
            // The initials stand in until a photo loads; the covers add nothing to what is read.
            listOf("AL", "Ann Leckie", "3 books")
        // No cover is named: the two covers are decoration.
        row.config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .none { it.contains("cover", ignoreCase = true) } shouldBe
            true
    }

    @Test
    fun `on a phone there are no covers`() {
        render(showCovers = false)

        composeRule.onAllNodesWithTag(PREVIEW_COVER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }
}
