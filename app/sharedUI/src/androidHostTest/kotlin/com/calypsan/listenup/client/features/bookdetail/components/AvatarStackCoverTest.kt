package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarCover
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaderFace
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A face tucked under its neighbour keeps its whole initials: they sit in the part of the circle left showing, not
 * centred under the circle on top, where the first or last letter was lost.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Text measured at its real width, so a pair of initials really is wide.
@RunWith(RobolectricTestRunner::class)
class AvatarStackCoverTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar resolves its collaborators through a composition Koin, which registers globally. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun face(isLive: Boolean = false) = ChapterReaderFace(userId = "u", displayName = "Walt Moss", isLive = isLive)

    private fun avatar(
        size: AvatarSize,
        covered: AvatarCover,
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                UserAvatar(
                    userId = "walt",
                    size = size,
                    fallbackName = "Walt Moss",
                    covered = covered,
                    modifier = Modifier.testTag("avatar"),
                )
            }
        }
    }

    /** Where the initials sit in the circle: their start and end gaps from its edges. */
    private fun initialsGaps(): Pair<Dp, Dp> {
        val circle = composeRule.onNodeWithTag("avatar").getUnclippedBoundsInRoot()
        val text = composeRule.onNodeWithText("WM", useUnmergedTree = true).getUnclippedBoundsInRoot()
        return text.left - circle.left to circle.right - text.right
    }

    @Test
    fun `the first circle sits on top, uncovered`() {
        coverAt(listOf(face(), face()), 0) shouldBe AvatarCover.None
    }

    @Test
    fun `a face after another loses the overlap and the cut round the one on top`() {
        coverAt(listOf(face(), face()), 1) shouldBe AvatarCover(start = 8.dp)
    }

    @Test
    fun `a face after someone listening now tucks in less, but loses their live ring too`() {
        coverAt(listOf(face(isLive = true), face()), 1) shouldBe AvatarCover(start = 6.dp)
    }

    @Test
    fun `the +N disc after the last face is covered like a face`() {
        coverAt(listOf(face(), face()), 2) shouldBe AvatarCover(start = 8.dp)
    }

    @Test
    fun `an uncovered avatar centres its initials, as it always has`() {
        avatar(AvatarSize.Compact, AvatarCover.None)
        val (start, end) = initialsGaps()
        (start - end).value.shouldBeLessThanOrEqualTo(0.5f)
        (end - start).value.shouldBeLessThanOrEqualTo(0.5f)
    }

    @Test
    fun `a chapter-row face covered at its start keeps its initials clear of the face on top`() {
        avatar(AvatarSize.Compact, AvatarCover(start = 8.dp))
        val (start, end) = initialsGaps()
        start shouldBeGreaterThanOrEqualTo 9.dp
        end shouldBeGreaterThanOrEqualTo 1.dp
    }

    @Test
    fun `a hero-line face covered at its end keeps its initials clear of the face over it`() {
        avatar(AvatarSize.Small, AvatarCover(end = 8.dp))
        val (start, end) = initialsGaps()
        start shouldBeGreaterThanOrEqualTo 1.dp
        end shouldBeGreaterThanOrEqualTo 9.dp
    }
}
