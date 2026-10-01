package com.calypsan.listenup.client.features.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.components.ProvideNowPlayingInsets
import com.calypsan.listenup.client.features.nowplaying.DockedNowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.DockedNowPlayingBarHeight
import com.calypsan.listenup.client.features.shell.components.AppNavigationSuite
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppNavigationSuiteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomBarShowsAllDestinations() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                )
            }
        }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onNodeWithText("Discover").assertIsDisplayed()
    }

    @Test
    fun collapsedRailExposesDestinations() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailCollapsed,
                    currentDestination = ShellDestination.Library,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                )
            }
        }
        // The expressive rail renders a visible label per destination; the merged
        // accessibility node exposes it as text (not the icon's contentDescription).
        composeRule.onNodeWithText("Library").assertIsDisplayed()
    }

    @Test
    fun tappingBottomBarDestinationInvokesCallback() {
        var selected: ShellDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = { selected = it },
                    onSignOutRequest = {},
                )
            }
        }
        composeRule.onNodeWithText("Discover").performClick()
        selected shouldBe ShellDestination.Discover
    }

    @Test
    fun tappingRailDestinationInvokesCallback() {
        var selected: ShellDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = { selected = it },
                    onSignOutRequest = {},
                )
            }
        }
        composeRule.onNodeWithText("Discover").performClick()
        selected shouldBe ShellDestination.Discover
    }

    @Test
    fun tappingRailLogoutRequestsSignOut() {
        var requests = 0
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOutRequest = { requests++ },
                )
            }
        }
        composeRule.onNodeWithText("Logout").performClick()
        requests shouldBe 1
    }

    /**
     * On a tablet the docked mini-player spans the whole window bottom, rail included. The rail must
     * end above it, or its bottom-pinned Logout sits under the bar: unseen, and unreachable.
     */
    @Test
    @Config(manifest = Config.NONE, sdk = [34], qualifiers = "w1280dp-h800dp")
    fun railLogoutStaysAboveTheDockedNowPlayingBar() {
        composeRule.setContent {
            MaterialTheme {
                ProvideNowPlayingInsets(barVisible = true, latchedFootprint = DockedNowPlayingBarHeight) {
                    Box(Modifier.fillMaxSize()) {
                        AppNavigationSuite(
                            navType = ShellNavType.RailExpanded,
                            currentDestination = ShellDestination.Home,
                            onDestinationSelected = {},
                            onSignOutRequest = {},
                            modifier = Modifier.fillMaxHeight(),
                        )
                        DockedNowPlayingBar(
                            state = dockedState,
                            progress = { dockedProgress },
                            isExpanded = false,
                            onTap = {},
                            onPlayPause = {},
                            onSkipBack = {},
                            onSkipForward = {},
                            onSeek = {},
                            onSpeedClick = {},
                            skipBackwardSec = 15,
                            skipForwardSec = 30,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag(DOCKED_BAR),
                        )
                    }
                }
            }
        }

        val logout = composeRule.onNodeWithText("Logout")
        logout.assertIsDisplayed()
        val barTop = composeRule.onNodeWithTag(DOCKED_BAR).getUnclippedBoundsInRoot().top
        logout.getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo barTop
    }

    @Test
    fun bottomBarLibraryCarriesTheHeldCount() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                    libraryBadgeCount = 3,
                )
            }
        }
        composeRule.onNodeWithContentDescription("3 books waiting for review", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun railLibraryCarriesTheHeldCount() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Library,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                    libraryBadgeCount = 1,
                )
            }
        }
        composeRule.onNodeWithContentDescription("1 book waiting for review", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun nothingHeldMeansNoBadge() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                    libraryBadgeCount = 0,
                )
            }
        }
        composeRule.onNodeWithContentDescription("waiting for review", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLargeCountIsReadInFullEvenWhenDrawnCapped() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOutRequest = {},
                    libraryBadgeCount = 120,
                )
            }
        }
        composeRule.onNodeWithContentDescription("120 books waiting for review", useUnmergedTree = true).assertExists()
    }

    private companion object {
        const val DOCKED_BAR = "docked-bar"

        val dockedProgress =
            PlaybackProgress(
                bookProgress = 0.25f,
                bookPositionMs = 60_000,
                bookDurationMs = 240_000,
                chapterProgress = 0.5f,
                chapterPositionMs = 30_000,
                chapterDurationMs = 60_000,
            )

        val dockedState =
            NowPlayingState.Active(
                bookId = "book-1",
                title = "The Way of Kings",
                author = "Brandon Sanderson",
                coverPath = "/nonexistent/cover.jpg",
                coverHash = null,
                authors = emptyList(),
                narrators = emptyList(),
                seriesId = null,
                seriesName = null,
                chapterTitle = "Prologue",
                chapterIndex = 0,
                totalChapters = 10,
                chapters = emptyList(),
                isPlaying = false,
                isBuffering = false,
                playbackSpeed = 1f,
                defaultPlaybackSpeed = 1f,
                volumeBoostDb = 0f,
                defaultVolumeBoostDb = 0f,
            )
    }
}
